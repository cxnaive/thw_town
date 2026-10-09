package cn.lunadeer.dominion.handler;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.PlayerDTO;
import cn.lunadeer.dominion.api.dtos.flag.EnvFlag;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.Limitation;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.events.dominion.DominionCreateEvent;
import cn.lunadeer.dominion.events.dominion.DominionDeleteEvent;
import cn.lunadeer.dominion.events.dominion.modify.*;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.DominionTransferManager;
import cn.lunadeer.dominion.managers.TownOutpostDeleteManager;
import cn.lunadeer.dominion.managers.PersonalDominionDeleteManager;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.providers.DominionProvider;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.BorderRenderUtil;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static cn.lunadeer.dominion.misc.Asserts.*;
import static cn.lunadeer.dominion.misc.Converts.toPlayer;
import static cn.lunadeer.dominion.misc.Others.getSubDominionsRecursive;

/**
 * Handles various dominion-related events such as creation, deletion, size change, renaming, transfer,
 * setting teleportation location, setting messages, setting map color, and setting flags.
 * This class implements the Listener interface to handle events in a Bukkit plugin.
 */
public class DominionProviderHandler extends DominionProvider {

    public static class DominionProviderHandlerText extends ConfigurationPart {
        public String createSuccess = "Create dominion {0} success.";
        public String createFailed = "Create dominion {0} failed, reason: {1}";
        public String personalCreationDisabled = "Personal dominions can no longer be created. Create or join a town instead (/town create).";
        public String subCreationDisabled = "Sub dominions can no longer be created.";
        public String legacyResizeDisabled = "Resizing legacy dominions is disabled on this server.";
        public String legacyDeleteDisabled = "Deleting dominions is disabled on this server.";
        public String townOutpostNoDelete = "Dominion {0} is town property; it cannot be deleted personally. The town disbands it, or an admin releases it.";
        public String outpostDeleteDenied = "Only the town's mayor, assistants or server administrators may delete an outpost.";
        public String outpostDeleteChanged = "The outpost was deleted or changed towns. Reopen its management page before trying again.";
        public String outpostDeleteConfirm = "Delete only outpost {0}? Its protection and permissions will be removed without a refund; blocks, child dominions and the town remain. Run /dom delete {0} confirm to proceed.";
        public String outpostDeleteSuccess = "Deleted and archived outpost {0}. Its protection was removed without a refund; blocks, child dominions and the town remain.";
        public String personalDeleteConfirm = "Run /dom delete {0} confirm to delete the following personal dominions: {1}. Town-owned branches and all their children will be preserved.";
        public String personalDeletePreserved = "Preserved town-owned branches and all their children: {0}.";
        public String deleteRefundFailed = "Dominion {0} was deleted, but its refund failed: {1}. Ask an administrator to check the payment; do not repeat deletion.";
        public String townOutpostNoTransfer = "Dominion {0} is town property and cannot be transferred.";
        public String autoAttachedToTown = "Dominion {0} was attached to town {1} because its new owner is a town member.";
        public String autoAttachFailed = "Dominion {0} was transferred successfully, but could not be attached to town {1}: {2}. It remains the new owner's personal dominion.";
        public String autoAttachRefreshFailed = "Dominion {0} was transferred and attached to town {1}, but refreshing its state or notification failed: {2}. Do not repeat the transfer.";

        public String expandSuccess = "Expand dominion {0} success.";
        public String expandFailed = "Expand dominion {0} failed, reason: {1}";
        public String contractSuccess = "Contract dominion {0} success.";
        public String contractFailed = "Contract dominion {0} failed, reason: {1}";
        public String resizeChargeEstimate = "Estimated resize charge for {0}: {1} {2}.";
        public String resizeRefundEstimate = "Estimated resize refund for {0}: {1} {2}.";
        public String townResizeChargeEstimate = "Estimated town-bank charge for {0}: {1} {2}.";
        public String townResizeRefundEstimate = "Estimated town-bank refund for {0}: {1} {2}.";
        public String resizeFreeEstimate = "Resize estimate for {0}: no charge or refund.";

        public String deleteSuccess = "Delete dominion {0} success.";
        public String deleteFailed = "Delete dominion {0} failed, reason: {1}";
        public String deleteConfirm = "Use command '{0}' to confirm delete the dominion {1} and its subs, this operation cannot be undone.";
        public String listSubDoms = "The dominion {0} has subs: {1}";

        public String renameFailed = "Rename dominion {0} failed, reason: {1}";
        public String renameSuccess = "Rename dominion {0} to {1} success.";
        public String sameName = "The new name is the same as the old name.";

        public String giveSuccess = "Give dominion {0} to {1} success.";
        public String giveForceAdminOnly = "Only OPs or players with dominion.admin may use give force. Use confirm for a normal transfer.";
        public String giveForceSuccess = "Force-transferred {0} to {1}; previous town links were removed. Updated {2} dominion(s).";
        public String giveResultRefreshFailed = "Transfer committed for {0}, but its current state could not be read; automatic attachment was not attempted. Check the dominion before retrying. Reason: {1}";
        public String giveConfirmCommand = "Run /dom give {0} {1} confirm to transfer this personal dominion and its personal sub-dominions. Administrators may use /dom give {0} {1} force to detach town land and transfer it.";
        public String giveFailed = "Give dominion {0} to other failed, reason: {1}";
        public String giveConfirm = "Use command '{0}' to confirm give the dominion {1} to {2}, this operation cannot be undone.";
        public String alreadyBelong = "The dominion {0} already belongs to {1}.";
        public String cannotGiveSub = "Dominion {0} is a sub-dominion, cannot give it to others.";

        public String tpLocationNotInDominion = "The teleportation location is not in the dominion {0}.";
        public String tpLocationSetSuccess = "Set teleportation location for dominion {0} success.";
        public String tpLocationSetFailed = "Set teleportation location for dominion {0} failed, reason: {1}";

        public String setEnterMessageSuccess = "Set enter message for dominion {0} success.";
        public String setEnterMessageFailed = "Set enter message for dominion {0} failed, reason: {1}";
        public String setLeaveMessageSuccess = "Set leave message for dominion {0} success.";
        public String setLeaveMessageFailed = "Set leave message for dominion {0} failed, reason: {1}";

        public String SetMapColorSuccess = "Set map color for dominion {0} success.";
        public String SetMapColorFailed = "Set map color for dominion {0} failed, reason: {1}";

        public String setEnvFlagSuccess = "Set env flag {0} to {1} success.";
        public String setEnvFlagFailed = "Set env flag {0} to {1} failed, reason: {2}";
        public String setGuestFlagSuccess = "Set guest flag {0} to {1} success.";
        public String setGuestFlagFailed = "Set guest flag {0} to {1} failed, reason: {2}";

    }

    public DominionProviderHandler(JavaPlugin plugin) {
        instance = this;
    }

    public static DominionProviderHandler getInstance() {
        return (DominionProviderHandler) instance;
    }

    @Override
    public CompletableFuture<DominionDTO> createDominion(@NotNull CommandSender operator,
                                                         @NotNull String name, @NotNull UUID owner,
                                                         @NotNull World world, @NotNull CuboidDTO cuboid,
                                                         @Nullable DominionDTO parent, boolean skipEconomy) {
        DominionCreateEvent event = new DominionCreateEvent(operator, name, owner, world, cuboid, null);
        event.setSkipEconomy(skipEconomy);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                // Town era gates (plan §5.3/§12.2): personal and sub dominion
                // creation is frozen; towns expand through TownManager, which
                // creates outposts directly and never passes through here.
                assertCreationAllowed(event.getOperator(), event.getWorld());
                if (event.getOperator() instanceof Player gatePlayer && !Others.bypassLimit(gatePlayer)) {
                    if (parent == null && !Configuration.landSystem.allowNewPersonalDominion) {
                        throw new DominionException(Language.dominionProviderHandlerText.personalCreationDisabled);
                    }
                    if (parent != null && !Configuration.landSystem.allowNewSubDominion) {
                        throw new DominionException(Language.dominionProviderHandlerText.subCreationDisabled);
                    }
                }
                DominionDOO toBeCreated = new DominionDOO(
                        event.getOwner(),
                        event.getName(),
                        event.getWorld().getUID(),
                        event.getCuboid(),
                        parent == null ? -1 : parent.getId()
                );
                // name check
                assertDominionName(event.getName());
                // amount check
                assertPlayerDominionAmount(event.getOperator(), event.getWorld().getUID(), parent != null);
                // size check
                assertDominionSize(event.getOperator(), event.getWorld().getUID(), event.getCuboid());
                //permission check
                if (parent != null) {
                    assertDominionOwner(event.getOperator(), parent);
                }

                // parent check
                assertWithinParent(event.getOperator(), toBeCreated, event.getCuboid());
                assertSubDepth(event.getOperator(), toBeCreated);
                // intersect check
                assertDominionIntersect(event.getOperator(), toBeCreated, event.getCuboid());
                // handle economy
                if (!event.isSkipEconomy()) {
                    assertEconomy(event.getOperator(), CuboidDTO.ZERO, toBeCreated.getCuboid(), parent != null);
                }
                // do db insert
                DominionDTO inserted = DominionDOO.insert(toBeCreated);
                BorderRenderUtil.showBorder(event.getOperator(), inserted);
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.createSuccess, event.getName());
                return inserted;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.createFailed, event.getName(), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> resizeDominion(@NotNull CommandSender operator,
                                                         @NotNull DominionDTO dominion,
                                                         @NotNull DominionReSizeEvent.TYPE type,
                                                         @NotNull DominionReSizeEvent.DIRECTION direction,
                                                         int size) {
        DominionReSizeEvent event = new DominionReSizeEvent(operator, dominion, type, direction, size);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            long amount = event.getNewCuboid().minusVolumeWith(event.getOldCuboid());
            if (amount == 0) {
                return null;
            }
            boolean expand = amount > 0;
            try {
                DominionDOO resizeDoo = (DominionDOO) event.getDominion();
                TownDOO resizeTown = null;
                if (resizeDoo.isTownOutpost()) {
                    // resize of a town outpost: mayor/assistant only (charged
                    // to the town bank below); outpost admins cannot resize.
                    resizeTown = TownManager.requireTown(resizeDoo.getTownId());
                    if (event.getOperator() instanceof Player resizePlayer) {
                        TownManager.assertManager(resizePlayer, resizeTown);
                    }
                } else if (event.getOperator() instanceof Player resizePlayer
                        && !Others.bypassLimit(resizePlayer)
                        && !Configuration.landSystem.legacyAllowResize) {
                    throw new DominionException(Language.dominionProviderHandlerText.legacyResizeDisabled);
                }
                if (!resizeDoo.isTownOutpost()) {
                    assertDominionOwner(event.getOperator(), event.getDominion());
                }
                if (resizeTown != null) {
                    assertTownOutpostResizeSize(event.getDominion().getWorldUid(),
                            event.getOldCuboid(), event.getNewCuboid());
                } else {
                    assertDominionSize(event.getOperator(), event.getDominion().getWorldUid(), event.getNewCuboid());
                }
                assertWithinParent(event.getOperator(), event.getDominion(), event.getNewCuboid());
                assertContainSubs(event.getOperator(), event.getDominion(), event.getNewCuboid());
                assertDominionIntersect(event.getOperator(), event.getDominion(), event.getNewCuboid());
                // All ownership/manager and geometry checks passed, so this
                // preview cannot disclose pricing for an unauthorized land.
                notifyResizeEstimate(event.getOperator(), event.getDominion(),
                        event.getOldCuboid(), event.getNewCuboid(), event.isSkipEconomy());
                if (resizeTown == null && !event.isSkipEconomy()) {
                    assertEconomy(event.getOperator(), event.getOldCuboid(), event.getNewCuboid(),
                            dominion.getParentDomId() != -1);
                }
                if (resizeTown != null) {
                    Player resizePlayer = event.getOperator() instanceof Player p ? p : null;
                    event.setDominion(TownManager.resizeTownOutpost(resizePlayer, resizeTown, resizeDoo,
                            event.getOldCuboid(), event.getNewCuboid(), event.isSkipEconomy()));
                } else {
                    event.setDominion(event.getDominion().setCuboid(event.getNewCuboid()));
                }
                BorderRenderUtil.showBorder(event.getOperator(), event.getDominion());
                if (expand) {
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.expandSuccess, event.getDominion().getName());
                } else {
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.contractSuccess, event.getDominion().getName());
                }
                return event.getDominion();
            } catch (Exception e) {
                if (expand) {
                    Notification.error(event.getOperator(), Language.dominionProviderHandlerText.expandFailed, event.getDominion().getName(), e.getMessage());
                } else {
                    Notification.error(event.getOperator(), Language.dominionProviderHandlerText.contractFailed, event.getDominion().getName(), e.getMessage());
                }
                return null;
            }
        });
    }

    /**
     * Show the same pricing policy that the resize transaction will use.
     * This is deliberately only a preview: all permission, bounds and balance
     * checks still happen in the existing atomic operation below.
     */
    private static void notifyResizeEstimate(@NotNull CommandSender operator,
                                              @NotNull DominionDTO dominion,
                                              @NotNull CuboidDTO before,
                                              @NotNull CuboidDTO after,
                                              boolean skipEconomy) {
        if (!(operator instanceof Player player)) return;
        boolean townOutpost = dominion instanceof DominionDOO doo && doo.isTownOutpost();
        boolean bypass = Others.bypassLimit(player);
        double estimate;
        if (townOutpost) {
            if (skipEconomy || bypass || !Configuration.town.outpostPricing.enable) {
                Notification.info(player, Language.dominionProviderHandlerText.resizeFreeEstimate,
                        dominion.getName());
                return;
            }
            estimate = TownManager.townOutpostPrice(before, after);
            if (estimate < 0 && dominion instanceof DominionDOO outpost) {
                long removedGrossCents = Math.round(-estimate * 100.0);
                long refundableGrossCents = Math.min(outpost.getTownPaidValueCents(), removedGrossCents);
                estimate = -Math.round(refundableGrossCents
                        * Configuration.town.outpostPricing.refundRate) / 100.0;
            }
        } else {
            Limitation.Economy economy = Configuration.getPlayerLimitation(player).economy;
            if (skipEconomy || bypass || !economy.enable
                    || (dominion.getParentDomId() != -1 && economy.freeForSubs)) {
                Notification.info(player, Language.dominionProviderHandlerText.resizeFreeEstimate,
                        dominion.getName());
                return;
            }
            long amount = economy.squareOnly
                    ? after.minusSquareWith(before)
                    : after.minusVolumeWith(before);
            estimate = amount * economy.pricePerBlock;
            if (estimate < 0) estimate *= economy.refundRate;
        }
        if (Math.abs(estimate) < 0.000001) {
            Notification.info(player, Language.dominionProviderHandlerText.resizeFreeEstimate,
                    dominion.getName());
        } else if (estimate > 0) {
            Notification.info(player, townOutpost
                            ? Language.dominionProviderHandlerText.townResizeChargeEstimate
                            : Language.dominionProviderHandlerText.resizeChargeEstimate,
                    dominion.getName(), estimate, VaultConnect.instance.currencyNamePluralOrEmpty());
        } else {
            Notification.info(player, townOutpost
                            ? Language.dominionProviderHandlerText.townResizeRefundEstimate
                            : Language.dominionProviderHandlerText.resizeRefundEstimate,
                    dominion.getName(), -estimate, VaultConnect.instance.currencyNamePluralOrEmpty());
        }
    }

    @Override
    public CompletableFuture<DominionDTO> deleteDominion(@NotNull CommandSender operator,
                                                         @NotNull DominionDTO dominion,
                                                         boolean skipEconomy,
                                                         boolean force) {
        DominionDeleteEvent event = new DominionDeleteEvent(operator, dominion);
        event.setForce(force);
        event.setSkipEconomy(skipEconomy);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                DominionDOO delDoo = (DominionDOO) event.getDominion();
                DominionDOO latest = DominionDOO.select(delDoo.getId());
                if (latest == null) throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
                if (delDoo.isTownOutpost() || latest.isTownOutpost()) {
                    if (!Objects.equals(delDoo.getTownId(), latest.getTownId())) {
                        throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
                    }
                    DominionDOO result = TownOutpostDeleteManager.delete(event.getOperator(), delDoo, event.isForce());
                    Notification.info(event.getOperator(), event.isForce()
                                    ? Language.dominionProviderHandlerText.outpostDeleteSuccess
                                    : Language.dominionProviderHandlerText.outpostDeleteConfirm,
                            result.getName());
                    return event.isForce() ? result : null;
                }
                PersonalDominionDeleteManager.Result result = PersonalDominionDeleteManager.delete(
                        event.getOperator(), delDoo.getId(), event.isForce());
                if (!event.isForce()) {
                    Notification.warn(event.getOperator(), Language.dominionProviderHandlerText.personalDeleteConfirm,
                            result.root().getName(), String.join(", ", result.deleted().stream().map(DominionDOO::getName).toList()));
                    if (!result.preserved().isEmpty()) Notification.info(event.getOperator(),
                            Language.dominionProviderHandlerText.personalDeletePreserved,
                            String.join(", ", result.preserved().stream().map(DominionDOO::getName).toList()));
                    return null;
                }
                for (DominionDOO deleted : result.deleted()) {
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.deleteSuccess, deleted.getName());
                    if (!event.isSkipEconomy()) {
                        try {
                            assertEconomy(event.getOperator(), deleted.getCuboid(), CuboidDTO.ZERO, deleted.getParentDomId() != -1);
                        } catch (Exception refundError) {
                            Notification.warn(event.getOperator(), Language.dominionProviderHandlerText.deleteRefundFailed,
                                    deleted.getName(), refundError.getMessage());
                        }
                    }
                }
                if (!result.preserved().isEmpty()) Notification.info(event.getOperator(),
                        Language.dominionProviderHandlerText.personalDeletePreserved,
                        String.join(", ", result.preserved().stream().map(DominionDOO::getName).toList()));
                return result.root();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.deleteFailed, event.getDominion().getName(), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> renameDominion(@NotNull CommandSender operator,
                                                         @NotNull DominionDTO dominion,
                                                         @NotNull String newName) {
        DominionRenameEvent event = new DominionRenameEvent(operator, dominion, newName);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), dominion);
                if (Objects.equals(event.getOldName(), event.getNewName())) {
                    throw new DominionException(Language.dominionProviderHandlerText.sameName);
                }
                assertDominionName(event.getNewName());
                event.setDominion(dominion.setName(event.getNewName()));
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.renameSuccess, event.getOldName(), event.getNewName());
                return event.getDominion();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.renameFailed, event.getOldName(), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> transferDominion(@NotNull CommandSender operator,
                                                           @NotNull DominionDTO dominion,
                                                           @NotNull PlayerDTO newOwner,
                                                           boolean force) {
        // Preserve the provider API's existing confirmation semantics.
        return transferDominion(operator, dominion, newOwner, force, false);
    }

    public CompletableFuture<DominionDTO> transferDominion(@NotNull CommandSender operator,
                                                           @NotNull DominionDTO dominion,
                                                           @NotNull PlayerDTO newOwner,
                                                           boolean confirmed, boolean adminForce) {
        if (adminForce && !Others.isAdministrator(operator)) {
            Notification.error(operator, Language.dominionProviderHandlerText.giveForceAdminOnly);
            return CompletableFuture.completedFuture(null);
        }
        DominionTransferEvent event = new DominionTransferEvent(operator, dominion, newOwner);
        event.setForce(confirmed);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            List<Integer> transferredIds = new ArrayList<>();
            try {
                UUID newOwnerUuid = event.getNewOwner().getUuid();
                String newOwnerName = event.getNewOwner().getLastKnownName();
                if (adminForce) {
                    transferredIds.addAll(DominionTransferManager.forceTransfer(
                            event.getOperator(), event.getDominion().getId(), newOwnerUuid));
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.giveForceSuccess,
                            event.getDominion().getName(), newOwnerName, transferredIds.size());
                } else {
                    DominionDOO giveDoo = (DominionDOO) event.getDominion();
                    if (giveDoo.isTownOutpost()) {
                        throw new DominionException(Language.dominionProviderHandlerText.townOutpostNoTransfer, giveDoo.getName());
                    }
                    assertDominionOwner(event.getOperator(), event.getDominion());
                    if (dominion.getParentDomId() != -1) {
                        throw new DominionException(Language.dominionProviderHandlerText.cannotGiveSub, event.getDominion().getName());
                    }
                    Player newOwnerBukkit = toPlayer(newOwnerUuid);
                    if (newOwnerUuid.equals(event.getDominion().getOwner())) {
                        throw new DominionException(Language.dominionProviderHandlerText.alreadyBelong,
                                event.getDominion().getName(), newOwnerName);
                    }
                    boolean operatorBypasses = !(event.getOperator() instanceof Player opPlayer)
                            || Others.bypassLimit(opPlayer);
                    if (!operatorBypasses) {
                        assertPlayerDominionAmount(newOwnerBukkit, event.getDominion().getWorldUid(), false);
                    }
                    List<DominionDTO> subDominions = getSubDominionsRecursive(event.getDominion());
                    if (!event.isForce()) {
                        if (!subDominions.isEmpty()) {
                            Notification.warn(event.getOperator(), Language.dominionProviderHandlerText.listSubDoms,
                                    event.getDominion().getName(), String.join(", ", subDominions.stream().map(DominionDTO::getName).toList()));
                        }
                        Notification.warn(event.getOperator(), Language.dominionProviderHandlerText.giveConfirmCommand,
                                event.getDominion().getName(), newOwnerName);
                        return null;
                    }
                    for (DominionDTO sub : subDominions) {
                        if (((DominionDOO) sub).isTownOutpost()) continue;
                        sub.setOwner(newOwnerUuid);
                        transferredIds.add(sub.getId());
                        Notification.info(event.getOperator(), Language.dominionProviderHandlerText.giveSuccess,
                                sub.getName(), newOwnerName);
                    }
                    event.setDominion(event.getDominion().setOwner(newOwnerUuid));
                    transferredIds.add(event.getDominion().getId());
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.giveSuccess,
                            event.getDominion().getName(), newOwnerName);
                }
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.giveFailed,
                        event.getDominion().getName(), e.getMessage());
                return null;
            }
            // Ownership has committed. Attachment failures must not report the
            // already-successful transfer as failed.
            if (adminForce) {
                try {
                    DominionDOO current = DominionDOO.select(event.getDominion().getId());
                    if (current == null) throw new java.sql.SQLException("Dominion no longer exists.");
                    event.setDominion(current);
                } catch (Exception refreshError) {
                    Notification.warn(event.getOperator(), Language.dominionProviderHandlerText.giveResultRefreshFailed,
                            event.getDominion().getName(), refreshError.getMessage());
                    return null;
                }
            }
            attachTransferredDominions(event, transferredIds);
            return event.getDominion();
        });
    }

    private void attachTransferredDominions(DominionTransferEvent event, List<Integer> transferredIds) {
        String townName = "-";
        boolean attachmentCommitted = false;
        try {
            UUID newOwner = event.getNewOwner().getUuid();
            List<TownMemberDOO> memberships = new ArrayList<>(TownMemberDOO.selectByPlayer(newOwner));
            memberships.sort(java.util.Comparator.comparingInt(m -> switch (m.getRole()) {
                case MAYOR -> 0;
                case ASSISTANT -> 1;
                case RESIDENT -> 2;
            }));
            TownDOO town = null;
            for (TownMemberDOO member : memberships) {
                TownDOO candidate = TownDOO.select(member.getTownId());
                if (candidate != null && candidate.getStatus() == TownDOO.TownStatus.ACTIVE) {
                    town = candidate;
                    break;
                }
            }
            if (town == null) return;
            townName = town.getName();
            List<DominionDOO> candidates = new ArrayList<>();
            for (int id : transferredIds) {
                DominionDOO current = DominionDOO.select(id);
                if (current != null && !current.isTownOutpost() && current.getOwner().equals(newOwner)) {
                    candidates.add(current);
                }
            }
            UUID attachOperator = event.getOperator() instanceof Player p ? p.getUniqueId() : newOwner;
            List<Integer> attached = TownManager.attachOwnedOutposts(town, candidates, attachOperator, newOwner);
            if (!attached.contains(event.getDominion().getId())) {
                throw new DominionException(Language.townManagerText.outpostAttachUnavailable, event.getDominion().getName());
            }
            attachmentCommitted = true;
            DominionDOO updated = DominionDOO.select(event.getDominion().getId());
            if (updated != null) event.setDominion(updated);
            Notification.info(event.getOperator(), Language.dominionProviderHandlerText.autoAttachedToTown,
                    event.getDominion().getName(), townName);
            Player online = org.bukkit.Bukkit.getPlayer(newOwner);
            if (online != null) Notification.info(online, Language.dominionProviderHandlerText.autoAttachedToTown,
                    event.getDominion().getName(), townName);
        } catch (Exception attachError) {
            String message = attachmentCommitted
                    ? Language.dominionProviderHandlerText.autoAttachRefreshFailed
                    : Language.dominionProviderHandlerText.autoAttachFailed;
            Notification.warn(event.getOperator(), message,
                    event.getDominion().getName(), townName, attachError.getMessage());
            Player online = org.bukkit.Bukkit.getPlayer(event.getNewOwner().getUuid());
            if (online != null) Notification.warn(online, message,
                    event.getDominion().getName(), townName, attachError.getMessage());
        }
    }

    @Override
    public CompletableFuture<DominionDTO> setDominionTpLocation(@NotNull CommandSender operator,
                                                                @NotNull DominionDTO dominion,
                                                                @NotNull Location newTpLocation) {
        DominionSetTpLocationEvent event = new DominionSetTpLocationEvent(operator, dominion, newTpLocation);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                DominionDTO d = CacheManager.instance.getCache().getDominionCache().getDominion(event.getNewTpLocation());
                if (d == null || !d.getId().equals(event.getDominion().getId())) {
                    throw new DominionException(Language.dominionProviderHandlerText.tpLocationNotInDominion, event.getDominion().getName());
                }
                event.setDominion(event.getDominion().setTpLocation(event.getNewTpLocation()));
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.tpLocationSetSuccess, event.getDominion().getName());
                return event.getDominion();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.tpLocationSetFailed, event.getDominion().getName(), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> setDominionMessage(@NotNull CommandSender operator,
                                                             @NotNull DominionDTO dominion,
                                                             @NotNull DominionSetMessageEvent.TYPE type,
                                                             @NotNull String newMessage) {
        DominionSetMessageEvent event = new DominionSetMessageEvent(operator, dominion, type, newMessage);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                if (event.getType() == DominionSetMessageEvent.TYPE.ENTER) {
                    event.setDominion(event.getDominion().setJoinMessage(event.getNewMessage()));
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.setEnterMessageSuccess, event.getDominion().getName());
                } else {
                    event.setDominion(event.getDominion().setLeaveMessage(event.getNewMessage()));
                    Notification.info(event.getOperator(), Language.dominionProviderHandlerText.setLeaveMessageSuccess, event.getDominion().getName());
                }
                return event.getDominion();
            } catch (Exception e) {
                if (event.getType() == DominionSetMessageEvent.TYPE.ENTER) {
                    Notification.error(event.getOperator(), Language.dominionProviderHandlerText.setEnterMessageFailed, event.getDominion().getName(), e.getMessage());
                } else {
                    Notification.error(event.getOperator(), Language.dominionProviderHandlerText.setLeaveMessageFailed, event.getDominion().getName(), e.getMessage());
                }
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> setDominionMapColor(@NotNull CommandSender operator,
                                                              @NotNull DominionDTO dominion,
                                                              @NotNull Color newColor) {
        DominionSetMapColorEvent event = new DominionSetMapColorEvent(operator, dominion, newColor);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                event.setDominion(event.getDominion().setColor(event.getNewColor()));
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.SetMapColorSuccess, event.getDominion().getName());
                return event.getDominion();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.SetMapColorFailed, event.getDominion().getName(), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> setDominionEnvFlag(@NotNull CommandSender operator,
                                                             @NotNull DominionDTO dominion,
                                                             @NotNull EnvFlag flag,
                                                             boolean newValue) {
        DominionSetEnvFlagEvent event = new DominionSetEnvFlagEvent(operator, dominion, flag, newValue);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionAdmin(event.getOperator(), event.getDominion());
                event.setDominion(event.getDominion().setEnvFlagValue(event.getFlag(), event.getNewValue()));
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.setEnvFlagSuccess, event.getFlag().getDisplayName(), cn.lunadeer.dominion.misc.DisplayNames.feature(event.getNewValue()));
                return event.getDominion();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.setEnvFlagFailed, event.getFlag().getDisplayName(), cn.lunadeer.dominion.misc.DisplayNames.feature(event.getNewValue()), e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<DominionDTO> setDominionGuestFlag(@NotNull CommandSender operator,
                                                               @NotNull DominionDTO dominion,
                                                               @NotNull PriFlag flag,
                                                               boolean newValue) {
        DominionSetGuestFlagEvent event = new DominionSetGuestFlagEvent(operator, dominion, flag, newValue);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionAdmin(event.getOperator(), event.getDominion());
                event.setDominion(event.getDominion().setGuestFlagValue(event.getFlag(), event.getNewValue()));
                Notification.info(event.getOperator(), Language.dominionProviderHandlerText.setGuestFlagSuccess, event.getFlag().getDisplayName(), cn.lunadeer.dominion.misc.DisplayNames.permission(event.getNewValue()));
                return event.getDominion();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.dominionProviderHandlerText.setGuestFlagFailed, event.getFlag().getDisplayName(), cn.lunadeer.dominion.misc.DisplayNames.permission(event.getNewValue()), e.getMessage());
                return null;
            }
        });
    }

}
