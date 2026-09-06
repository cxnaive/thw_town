package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared, persistent item contributions used to advance town levels. */
public final class TownLevelManager {

    public static class TownLevelManagerText extends ConfigurationPart {
        public String memberOnly = "Only members of {0} may contribute to its level task.";
        public String progressionClosed = "Town {0} is level {1}; member progression is currently open through level {2}.";
        public String requirementMissing = "No shared task is configured for town level {0}.";
        public String noItems = "You do not have any {0} to submit.";
        public String contributionAccepted = "Submitted {0} x {1} to {2}'s level {3} task ({4}/{5}).";
        public String contributionFailedRefunded = "The contribution could not be saved. Removed items were returned: {0}";
        public String contributionChanged = "The town task changed before submission; your items were returned.";
        public String levelUp = "Town {0} reached level {1}! Outpost limit: {2} total / {3} per world.";
        public String droppedRefund = "Your inventory was full; {0} returned item(s) were dropped at your feet.";
        public String netheriteIngot = "Netherite Ingot";
        public String sculkCatalyst = "Sculk Catalyst";
        public String echoShard = "Echo Shard";
        public String heavyCore = "Heavy Core";
    }

    public enum State {
        AVAILABLE,
        OPEN_MAX,
        MISSING_REQUIREMENT
    }

    public record ProgressSnapshot(@NotNull State state,
                                   int currentLevel,
                                   int systemMaxLevel,
                                   int progressionMaxLevel,
                                   int targetLevel,
                                   @Nullable Material material,
                                   int required,
                                   long submitted) {
        public long remaining() {
            return Math.max(0, (long) required - submitted);
        }

        public boolean available() {
            return state == State.AVAILABLE && material != null;
        }
    }

    private record ContributionResult(int accepted, long submitted, boolean upgraded,
                                      int resultingLevel, boolean taskChanged) {
    }

    private record RemovedItems(@NotNull List<ItemStack> stacks, int total) {
        private static @NotNull RemovedItems empty() {
            return new RemovedItems(List.of(), 0);
        }
    }

    private record RefundDelivery(@NotNull List<ItemStack> pending, int dropped) {
    }

    /**
     * Refunds waiting for a player who left while the async database step was
     * in flight. The queue is drained on the player's entity thread and on
     * the next join, so a retired Folia entity cannot consume a refund.
     */
    private static final ConcurrentHashMap<UUID, List<ItemStack>> pendingRefunds =
            new ConcurrentHashMap<>();

    public static @NotNull ProgressSnapshot snapshot(@NotNull TownDOO town) throws Exception {
        int current = TownManager.effectiveLevel(town);
        int openMax = Math.min(Configuration.town.progressionMaxLevel, Configuration.town.maxLevel);
        if (current >= openMax || current >= Configuration.town.maxLevel) {
            return new ProgressSnapshot(State.OPEN_MAX, current, Configuration.town.maxLevel,
                    openMax, current, null, 0, 0);
        }

        int target = current + 1;
        Configuration.Town.TownLevelRequirement requirement =
                Configuration.town.levelRequirements.get(target);
        if (requirement == null) {
            return new ProgressSnapshot(State.MISSING_REQUIREMENT, current,
                    Configuration.town.maxLevel, openMax, target, null, 0, 0);
        }
        Material material = Material.matchMaterial(requirement.material);
        if (material == null) {
            return new ProgressSnapshot(State.MISSING_REQUIREMENT, current,
                    Configuration.town.maxLevel, openMax, target, null, 0, 0);
        }
        long submitted = selectSubmitted(town.getId(), target, requirementKey(requirement));
        return new ProgressSnapshot(State.AVAILABLE, current, Configuration.town.maxLevel,
                openMax, target, material, requirement.amount,
                Math.min(submitted, requirement.amount));
    }

    /**
     * Starts an asynchronous contribution flow. Inventory reads/writes always
     * run on the player's entity scheduler; database work remains async.
     */
    public static void contributeFromInventory(@NotNull Player player, @NotNull TownDOO town,
                                               @Nullable Runnable after) {
        if (CacheManager.instance.getTownCache().getMember(town.getId(), player.getUniqueId()) == null) {
            notifyEntity(player, () -> Notification.error(player,
                    Language.townLevelManagerText.memberOnly, town.getName()));
            return;
        }
        Scheduler.runTaskAsync(() -> {
            try {
                ProgressSnapshot task = snapshot(TownManager.requireTown(town.getId()));
                if (task.state() == State.OPEN_MAX) {
                    notifyEntity(player, () -> Notification.info(player,
                            Language.townLevelManagerText.progressionClosed,
                            town.getName(), task.currentLevel(), task.progressionMaxLevel()));
                    return;
                }
                if (!task.available()) {
                    notifyEntity(player, () -> Notification.error(player,
                            Language.townLevelManagerText.requirementMissing, task.targetLevel()));
                    return;
                }

                // A lowered requirement may already be complete. Let the DB
                // transaction promote it without asking for another item.
                if (task.remaining() == 0) {
                    persistContribution(player, town, task, RemovedItems.empty(), after);
                    return;
                }
                notifyEntity(player, () -> {
                    RemovedItems removed = removeItems(player, task.material(),
                            (int) Math.min(Integer.MAX_VALUE, task.remaining()));
                    if (removed.total() <= 0) {
                        Notification.warn(player, Language.townLevelManagerText.noItems,
                                displayMaterial(task.material()));
                        return;
                    }
                    Scheduler.runTaskAsync(() -> persistContribution(player, town, task, removed, after));
                });
            } catch (Exception e) {
                notifyEntity(player, () -> Notification.error(player, e));
            }
        });
    }

    private static void persistContribution(@NotNull Player player, @NotNull TownDOO town,
                                            @NotNull ProgressSnapshot task,
                                            @NotNull RemovedItems removed,
                                            @Nullable Runnable after) {
        Configuration.Town.TownLevelRequirement requirement =
                Configuration.town.levelRequirements.get(task.targetLevel());
        if (requirement == null || task.material() == null
                || !task.material().name().equals(requirement.material)) {
            refundAndNotify(player, removed,
                    Language.townLevelManagerText.contributionChanged, after);
            return;
        }
        ContributionResult result;
        try {
            result = recordContribution(town.getId(), task.currentLevel(),
                    task.targetLevel(), requirementKey(requirement), requirement.amount, removed.total());
        } catch (Exception e) {
            refundAndNotify(player, removed,
                    Language.townLevelManagerText.contributionFailedRefunded
                            .replace("{0}", e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage()),
                    after);
            return;
        }

        // recordContribution has committed at this point. Queue any overage
        // before cache/UI/broadcast work so a player quit cannot lose it.
        int refund = removed.total() - result.accepted();
        if (refund > 0) queueRefund(player.getUniqueId(), removed, refund);

        TownDOO upgraded = null;
        if (result.upgraded() || result.taskChanged()) {
            CacheManager.instance.getTownCache().reloadNow();
        }
        if (result.upgraded()) {
            upgraded = CacheManager.instance.getTownCache().getTown(town.getId());
        }
        TownDOO finalUpgraded = upgraded;
        try {
            notifyEntity(player, () -> {
                deliverPendingRefunds(player);
                if (result.taskChanged()) {
                    Notification.warn(player, Language.townLevelManagerText.contributionChanged);
                } else if (result.accepted() > 0) {
                    Notification.info(player, Language.townLevelManagerText.contributionAccepted,
                            displayMaterial(task.material()), result.accepted(), town.getName(),
                            task.targetLevel(), result.submitted(), requirement.amount);
                }
                if (after != null) after.run();
            });
        } catch (Exception e) {
            XLogger.warn("Town level contribution was committed, but player notification failed: {0}",
                    e.getMessage());
        }
        if (finalUpgraded != null) {
            try {
                broadcastLevelUp(finalUpgraded, result.resultingLevel());
            } catch (Exception e) {
                XLogger.warn("Town level {0} upgrade broadcast failed: {1}",
                        result.resultingLevel(), e.getMessage());
            }
        }
    }

    private static @NotNull ContributionResult recordContribution(int townId, int expectedLevel,
                                                                    int targetLevel,
                                                                    @NotNull String requirementKey,
                                                                    int required, int offered) throws Exception {
        Connection conn = DatabaseManager.instance.getConnection();
        boolean committed = false;
        try {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Cross-database row lock: a no-op write serializes concurrent
                // contributors on MySQL/PgSQL and obtains SQLite's write lock.
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE town SET level = level WHERE id = ?")) {
                    ps.setInt(1, townId);
                    ps.executeUpdate();
                }

                int currentLevel;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT level FROM town WHERE id = ?")) {
                    ps.setInt(1, townId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new IllegalStateException("Town no longer exists.");
                        currentLevel = rs.getInt(1);
                    }
                }
                if (currentLevel != expectedLevel) {
                    conn.commit();
                    committed = true;
                    return new ContributionResult(0, 0, false, currentLevel, true);
                }

                long existing = 0;
                boolean progressExists = false;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT submitted FROM town_level_progress "
                                + "WHERE town_id = ? AND target_level = ? AND requirement_key = ?")) {
                    ps.setInt(1, townId);
                    ps.setInt(2, targetLevel);
                    ps.setString(3, requirementKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            progressExists = true;
                            existing = Math.max(0, rs.getLong(1));
                        }
                    }
                }

                int accepted = (int) Math.min(Math.max(0L, (long) required - existing), offered);
                long submitted = Math.min(required, Math.addExact(existing, accepted));
                if (accepted > 0) {
                    if (progressExists) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE town_level_progress SET submitted = ? "
                                        + "WHERE town_id = ? AND target_level = ? AND requirement_key = ?")) {
                            ps.setLong(1, submitted);
                            ps.setInt(2, townId);
                            ps.setInt(3, targetLevel);
                            ps.setString(4, requirementKey);
                            if (ps.executeUpdate() != 1) {
                                throw new IllegalStateException("Town task progress changed concurrently.");
                            }
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO town_level_progress "
                                        + "(town_id, target_level, requirement_key, submitted) VALUES (?, ?, ?, ?)")) {
                            ps.setInt(1, townId);
                            ps.setInt(2, targetLevel);
                            ps.setString(3, requirementKey);
                            ps.setLong(4, submitted);
                            ps.executeUpdate();
                        }
                    }
                }

                boolean upgraded = submitted >= required;
                if (upgraded) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE town SET level = ? WHERE id = ? AND level = ?")) {
                        ps.setInt(1, targetLevel);
                        ps.setInt(2, townId);
                        ps.setInt(3, expectedLevel);
                        if (ps.executeUpdate() != 1) {
                            throw new IllegalStateException("Town level changed concurrently.");
                        }
                    }
                }
                conn.commit();
                committed = true;
                return new ContributionResult(accepted, submitted, upgraded,
                        upgraded ? targetLevel : currentLevel, false);
            } catch (Exception e) {
                if (!committed) {
                    try {
                        conn.rollback();
                    } catch (Exception rollbackError) {
                        e.addSuppressed(rollbackError);
                    }
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (Exception cleanupError) {
                    // A cleanup failure after commit must not be reported as
                    // a failed contribution: that would refund committed items.
                    XLogger.warn("Failed to restore contribution DB connection state: {0}",
                            cleanupError.getMessage());
                }
            }
        } finally {
            try {
                conn.close();
            } catch (Exception closeError) {
                // Same commit boundary rule as above. Hikari will retire a
                // broken connection; the contribution result stays final.
                XLogger.warn("Failed to close contribution DB connection: {0}",
                        closeError.getMessage());
            }
        }
    }

    private static long selectSubmitted(int townId, int targetLevel,
                                        @NotNull String requirementKey) throws Exception {
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT submitted FROM town_level_progress "
                             + "WHERE town_id = ? AND target_level = ? AND requirement_key = ?")) {
            ps.setInt(1, townId);
            ps.setInt(2, targetLevel);
            ps.setString(3, requirementKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getLong(1)) : 0;
            }
        }
    }

    private static @NotNull String requirementKey(
            @NotNull Configuration.Town.TownLevelRequirement requirement) {
        return requirement.type + ":" + requirement.material;
    }

    private static @NotNull RemovedItems removeItems(@NotNull Player player,
                                                     @NotNull Material material, int wanted) {
        int remaining = wanted;
        List<ItemStack> removed = new ArrayList<>();
        int storageSize = player.getInventory().getStorageContents().length;
        for (int slot = 0; slot < storageSize && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(stack.getAmount(), remaining);
            ItemStack taken = stack.clone();
            taken.setAmount(take);
            removed.add(taken);
            if (take == stack.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            remaining -= take;
        }
        return new RemovedItems(List.copyOf(removed), wanted - remaining);
    }

    private static @NotNull List<ItemStack> selectRefundStacks(@NotNull RemovedItems removed, int amount) {
        if (amount <= 0) return List.of();
        int remaining = amount;
        List<ItemStack> refund = new ArrayList<>();
        for (int i = removed.stacks().size() - 1; i >= 0 && remaining > 0; i--) {
            ItemStack original = removed.stacks().get(i);
            ItemStack stack = original.clone();
            int take = Math.min(original.getAmount(), remaining);
            stack.setAmount(take);
            refund.add(stack);
            remaining -= take;
        }
        return List.copyOf(refund);
    }

    private static @NotNull RefundDelivery refundItemStacks(@NotNull Player player,
                                                             @NotNull List<ItemStack> stacks) {
        List<ItemStack> pending = new ArrayList<>();
        int dropped = 0;
        for (ItemStack original : stacks) {
            ItemStack stack = original.clone();
            Map<Integer, ItemStack> leftovers;
            try {
                leftovers = player.getInventory().addItem(stack);
            } catch (RuntimeException e) {
                pending.add(original.clone());
                XLogger.warn("Failed to add a pending town task refund to {0}'s inventory: {1}",
                        player.getName(), e.getMessage());
                continue;
            }
            for (ItemStack leftover : leftovers.values()) {
                try {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    dropped += leftover.getAmount();
                } catch (RuntimeException e) {
                    pending.add(leftover.clone());
                    XLogger.warn("Failed to drop a pending town task refund for {0}: {1}",
                            player.getName(), e.getMessage());
                }
            }
        }
        return new RefundDelivery(List.copyOf(pending), dropped);
    }

    private static void queueRefund(@NotNull UUID playerUuid, @NotNull RemovedItems removed, int amount) {
        List<ItemStack> refund = selectRefundStacks(removed, amount);
        if (refund.isEmpty()) return;
        pendingRefunds.compute(playerUuid, (uuid, existing) -> {
            List<ItemStack> merged = new ArrayList<>(existing == null ? List.of() : existing);
            merged.addAll(refund);
            return List.copyOf(merged);
        });
        schedulePendingRefundDelivery(playerUuid);
    }

    private static void schedulePendingRefundDelivery(@NotNull UUID playerUuid) {
        Scheduler.runTask(() -> {
            Player current = Bukkit.getPlayer(playerUuid);
            if (current == null) return;
            Scheduler.runEntityTask(() -> deliverPendingRefunds(current), current,
                    () -> schedulePendingRefundDelivery(playerUuid));
        });
    }

    /** Called by the join listener; the actual inventory work is entity-local. */
    public static void retryPendingRefunds(@NotNull Player player) {
        Scheduler.runEntityTask(() -> deliverPendingRefunds(player), player);
    }

    private static void deliverPendingRefunds(@NotNull Player player) {
        if (!player.isOnline()) return;
        AtomicInteger dropped = new AtomicInteger();
        pendingRefunds.computeIfPresent(player.getUniqueId(), (uuid, stacks) -> {
            RefundDelivery delivery = refundItemStacks(player, stacks);
            dropped.set(delivery.dropped());
            return delivery.pending().isEmpty() ? null : delivery.pending();
        });
        if (dropped.get() > 0) {
            Notification.warn(player, Language.townLevelManagerText.droppedRefund, dropped.get());
        }
    }

    private static void refundAndNotify(@NotNull Player player,
                                        @NotNull RemovedItems removed, @NotNull String message,
                                        @Nullable Runnable after) {
        queueRefund(player.getUniqueId(), removed, removed.total());
        notifyEntity(player, () -> {
            deliverPendingRefunds(player);
            Notification.error(player, message);
            if (after != null) after.run();
        });
    }

    public static void setLevelByOperator(int townId, int level) throws Exception {
        try (Connection conn = DatabaseManager.instance.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE town SET level = ? WHERE id = ?")) {
                    ps.setInt(1, level);
                    ps.setInt(2, townId);
                    if (ps.executeUpdate() != 1) {
                        throw new IllegalStateException("Town no longer exists.");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM town_level_progress WHERE town_id = ? AND target_level > ?")) {
                    ps.setInt(1, townId);
                    ps.setInt(2, level);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static void broadcastLevelUp(@NotNull TownDOO town, int level) {
        String total = formatLimit(TownManager.outpostLimit(town));
        String perWorld = formatLimit(TownManager.outpostLimitPerWorld(town));
        CacheManager.instance.getTownCache().getMembersOf(town.getId()).forEach(member -> {
            Player online = Dominion.instance.getServer().getPlayer(member.getPlayerUuid());
            if (online != null) {
                notifyEntity(online, () -> Notification.info(online,
                        Language.townLevelManagerText.levelUp,
                        town.getName(), level, total, perWorld));
            }
        });
    }

    private static void notifyEntity(@NotNull Player player, @NotNull Runnable action) {
        Scheduler.runEntityTask(action, player);
    }

    public static @NotNull String displayMaterial(@Nullable Material material) {
        if (material == null) return "-";
        // HEAVY_CORE only exists on 1.21+. Match names to retain the core's
        // 1.20.1 API compatibility while localizing newer task materials.
        return switch (material.name()) {
            case "NETHERITE_INGOT" -> Language.townLevelManagerText.netheriteIngot;
            case "SCULK_CATALYST" -> Language.townLevelManagerText.sculkCatalyst;
            case "ECHO_SHARD" -> Language.townLevelManagerText.echoShard;
            case "HEAVY_CORE" -> Language.townLevelManagerText.heavyCore;
            default -> material.name();
        };
    }

    private static @NotNull String formatLimit(int limit) {
        return limit == -1 ? "∞" : String.valueOf(limit);
    }

    private TownLevelManager() {
    }
}
