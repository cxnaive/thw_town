package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.TownCache;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.GroupDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import cn.lunadeer.dominion.events.CallableEvent;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.syntax.Show.Show;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static cn.lunadeer.dominion.misc.Asserts.assertDominionIntersect;
import static cn.lunadeer.dominion.misc.Asserts.assertCreationAllowed;
import static cn.lunadeer.dominion.misc.Asserts.assertDominionName;
import static cn.lunadeer.dominion.misc.Asserts.assertTownOutpostSize;
import static cn.lunadeer.dominion.misc.Others.bypassLimit;

/**
 * Town business logic: lifecycle (create/disband/join/leave/kick/transfer),
 * attachment with member merging, trust, town bank and town expansion.
 * <p>
 * Attachment invariants (see TOWN_SYSTEM_UPGRADE_PLAN §3/§4):
 * - town_id only changes on explicit events, never follows a player;
 * - owner_uuid on a town outpost is a contributor record, not a permission;
 * - legacy-attached outposts carry attached_by (the operator of the attach
 *   event); town-native outposts (founding/remote) are born attached with
 *   attached_by NULL — this is how disband distinguishes contributed land
 *   from town-native land;
 * - old dominion_member rows are archived, never hard-deleted without a copy.
 */
public class TownManager {

    /**
     * Town mutations deliberately run off-thread because they perform real
     * database I/O, while Bukkit lifecycle events are synchronous events.
     * Bridge only the event dispatch back to the server thread and wait for
     * listeners before continuing the surrounding transaction/rollback flow.
     */
    private static void callLifecycleEvent(@NotNull CallableEvent event) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            event.call();
            return;
        }
        CompletableFuture<Void> called = new CompletableFuture<>();
        Scheduler.runTask(() -> {
            try {
                event.call();
                called.complete(null);
            } catch (Throwable throwable) {
                called.completeExceptionally(throwable);
            }
        });
        try {
            called.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    public static class TownManagerText extends ConfigurationPart {
        public String townNameShouldNotEmpty = "Town name should not be empty.";
        public String townNameInvalid = "Town name should not contain space, dot, section sign, ampersand, tilde or comma.";
        public String townNameExist = "Town name {0} already exists.";
        public String townNameUnchanged = "The town is already named {0}.";
        public String onlyMayorCanRename = "Only the mayor of {0} can rename the town.";
        public String renameSuccess = "Town {0} renamed to {1}.";
        public String townNotExist = "Town {0} does not exist.";
        public String alreadyInTown = "You are already a member of town {0}.";
        public String notInTown = "You are not a member of town {0}.";
        public String notTownManager = "Only the mayor or assistants of {0} can do this.";
        public String maxMembershipsReached = "You already belong to {0} town(s), the server limit is {1}.";
        public String createSuccess = "Town {0} created.";
        public String createSuccessFounding = "Town {0} created with a founding outpost.";
        public String disbandSuccess = "Town {0} disbanded. {1} outpost(s) dissolved and archived.";
        public String disbandSuccessReturn = "Town {0} disbanded: {1} outpost(s) returned to contributors, {2} dissolved.";
        public String disbandEnclaveDissolved = "Your dominion {0} was dissolved together with town {1}.";
        public String joinSuccess = "You joined town {0}.";
        public String attachHint = "You have {0} dominion(s) that can be attached to the town. Use /town attach to select.";
        public String townGroupNameInvalid = "Town group names may contain only lowercase letters, numbers, '_' and '-'.";
        public String townGroupNameTooLong = "Town group name may not exceed 32 characters.";
        public String townGroupDisplayTooLong = "Town group display name may not exceed 64 characters.";
        public String townGroupExists = "Town group {0} already exists in {1}.";
        public String townGroupNotExist = "Town group {0} does not exist in {1}.";
        public String townGroupCreated = "Created town group {0} in {1}.";
        public String townGroupDisplayUpdated = "Renamed town group {0} to {1}.";
        public String townGroupDeleted = "Deleted town group {0} from {1}; assigned members now use @town.";
        public String townGroupMemberAssigned = "Assigned {0} to town group {1}.";
        public String townGroupMemberRemoved = "Removed {0} from their town group; they now use @town.";
        public String townGroupMirrorReserved = "Town-managed group mirrors cannot be deleted, renamed, or edited here; manage membership from the town.";
        public String townGroupMirrorTag = " §7[From town]";
        public String templateModeRequired = "Town {0} has not upgraded to town template permission mode.";
        public String templateModeAlreadyEnabled = "Town {0} already uses town template permission mode.";
        public String templateModeMayorOnly = "Only the mayor of {0} can perform this irreversible upgrade.";
        public String templateModeUpgradeSuccess = "Town {0} upgraded to template permission mode. {1} outpost(s) now inherit town templates. Please review the templates now.";
        public String templateFlagUpdated = "Set town template {0} flag {1} to {2}.";
        public String outpostOverrideCleared = "Flag {0} on group {1} now inherits the town template.";
        public String leaveSuccess = "You left town {0}. Your land stays with the town.";
        public String mayorCannotLeave = "The mayor cannot leave; transfer the mayor role or disband the town first.";
        public String kickSuccess = "Kicked {0} from town {1}.";
        public String cannotKickManager = "You cannot kick the mayor or an assistant.";
        public String onlyMayorCanDisband = "Only the mayor of {0} can disband the town.";
        public String onlyMayorCanTransfer = "Only the current mayor of {0} can transfer the mayor role.";
        public String transferSuccess = "Mayor of {0} transferred to {1}.";
        public String setRoleSuccess = "Set {0}'s role to {1} in town {2}.";
        public String setRoleNotify = "Your role in town {1} is now {0}.";
        public String useTransferForMayor = "The mayor role can only change hands via /town transfer.";
        public String onlyMayorCanSetRole = "Only the mayor can promote or demote members.";
        // Confirmation messages for destructive actions
        public String confirmKick = "§cClick again within 5 seconds to kick {0}.";
        public String confirmPromote = "§aClick again within 5 seconds to promote {0} to assistant.";
        public String confirmDemote = "§cClick again within 5 seconds to demote {0} to resident.";
        public String confirmTransfer = "§6§lClick again within 5 seconds to transfer mayor to {0}. This cannot be undone!";
        public String targetNotMember = "{0} is not a member of town {1}.";
        public String trustSuccess = "Trusted {0} on {1} (scope {2}).";
        public String untrustSuccess = "Removed trust of {0} on {1} (scope {2}).";
        public String trustDisabled = "The trust system is disabled on this server.";
        public String trustTargetOutpostNotInTown = "Outpost {0} does not belong to town {1}.";
        public String depositSuccess = "Deposited {0} into the bank of {1}. New balance: {2}.";
        public String withdrawSuccess = "Withdrew {0} from the bank of {1}. New balance: {2}.";
        public String insufficientTownFunds = "Town bank of {0} cannot afford {1} (balance: {2}).";
        public String invalidAmount = "Invalid amount: {0}.";
        public String outpostCreateSuccess = "Outpost {0} created for town {1}, cost {2} {3} from the town bank.";
        public String outpostRemoteDisabled = "Remote outposts are disabled; towns may only resize existing outposts.";
        public String outpostAmountExceeded = "Town {0} has reached its level {1} outpost limit ({2}).";
        public String townLevelAdminOnly = "Only an OP may set a town's level.";
        public String townLevelInvalid = "Town level must be between 1 and {0}.";
        public String townLevelSet = "Set town {0} to level {1}; outpost limit is {2} total / {3} per world.";
        public String outpostAttachUnavailable = "Dominion {0} is no longer available to attach; refresh and retry.";
        public String foundingRequired = "You own no land: a founding outpost location is required to create a town.";
        public String releaseSuccess = "Outpost {0} released from town {1}, returned to contributor {2}.";
        public String releaseNotOutpost = "Dominion {0} is not a town outpost.";
        public String releaseFoundingDenied = "A founding outpost can never be released; it dissolves with its town.";
        public String releaseAdminOnly = "Only an OP may use administrator town arbitration.";
        public String releaseMayorContributedOnly = "Only the mayor may return contributed outposts of their own town; town-created outposts cannot be returned.";
        public String onlyMemberCanDeposit = "Only members of {0} can deposit into its bank.";
        public String outpostAdminSet = "Outpost admin of {0} set to {1}.";
        public String outpostAdminCleared = "Outpost admin of {0} cleared.";
        public String outpostAdminNotMember = "Outpost admin must be a member of town {0}.";
        public String mergeMemberTrusted = "Your membership of {1} was converted to a trust grant for that outpost (the land was attached to a town).";
        public String townGroupDisplayName = "Town Members";
    }

    /**
     * Built-in group carrying the town-member default flags of an outpost
     * (plan §5.1: reuse the existing group mechanism instead of a new flag
     * store). Mayors manage these flags through the normal group flag
     * commands; the reserved name keeps the lookup unambiguous.
     */
    public static final String TOWN_MEMBER_GROUP_NAME = "@town";

    /**
     * Localized display name for the built-in town member group. The
     * internal key "@town" stays as-is (it is the lookup key on every
     * attached outpost); only the presentation layer localizes.
     */
    public static @NotNull String groupDisplayName(@NotNull GroupDTO group) {
        if (group.getNamePlain().equals(TOWN_MEMBER_GROUP_NAME)) {
            return Language.townManagerText.townGroupDisplayName;
        }
        return group.getNameColoredBukkit();
    }

    public static boolean isTownManagedGroup(@NotNull GroupDTO group, @NotNull DominionDTO dominion) {
        if (!(dominion instanceof DominionDOO doo) || !doo.isTownOutpost()) return false;
        return TOWN_MEMBER_GROUP_NAME.equals(group.getNamePlain()) || group.getTownGroupId() != null;
    }

    public static @NotNull String townGroupDisplayName(@NotNull GroupDTO group) {
        String display = groupDisplayName(group);
        return group.getTownGroupId() == null ? display : display + Language.townManagerText.townGroupMirrorTag;
    }

    // ------------------------------------------------------------------
    // Trust flag categories (plan §4.5 mapping, both directions)
    // ------------------------------------------------------------------

    public enum TrustCategory {
        BUILD,
        DESTROY,
        SWITCH,
        ITEM_USE
    }

    private static final Map<String, TrustCategory> FLAG_TO_TRUST = new HashMap<>();

    static {
        for (String f : new String[]{"place", "sowing", "vehicle_spawn"})
            FLAG_TO_TRUST.put(f, TrustCategory.BUILD);
        for (String f : new String[]{"break", "harvest", "vehicle_destroy"})
            FLAG_TO_TRUST.put(f, TrustCategory.DESTROY);
        for (String f : new String[]{"door", "button", "lever", "pressure", "repeater", "comparer",
                "note_block", "container", "hopper", "item_frame_interactive", "edit_sign", "dragon_egg",
                "jukebox", "lectern", "bookshelf", "bed", "anchor", "cake"})
            FLAG_TO_TRUST.put(f, TrustCategory.SWITCH);
        for (String f : new String[]{"craft", "crafter", "anvil", "enchant", "brew", "beacon", "use_map",
                "ignite", "shoot", "egg", "hook", "trade", "feed", "shear", "leash", "dye", "honey",
                "pick_up", "drop_item"})
            FLAG_TO_TRUST.put(f, TrustCategory.ITEM_USE);
    }

    public static @Nullable TrustCategory trustCategoryOf(@NotNull PriFlag flag) {
        return FLAG_TO_TRUST.get(flag.getFlagName());
    }

    public static boolean trustGrants(@NotNull TownTrustDOO trust, @NotNull TrustCategory category) {
        return switch (category) {
            case BUILD -> trust.isBuild();
            case DESTROY -> trust.isDestroy();
            case SWITCH -> trust.isSwitch();
            case ITEM_USE -> trust.isItemUse();
        };
    }

    // ------------------------------------------------------------------
    // Lookup helpers
    // ------------------------------------------------------------------

    private static TownCache cache() {
        return CacheManager.instance.getTownCache();
    }

    public static @NotNull TownDOO requireTown(@NotNull String name) throws DominionException {
        TownDOO town = cache().getTown(name);
        if (town == null) {
            throw new DominionException(Language.townManagerText.townNotExist, name);
        }
        return town;
    }

    public static @NotNull TownDOO requireTown(@NotNull Integer id) throws DominionException {
        TownDOO town = cache().getTown(id);
        if (town == null) {
            throw new DominionException(Language.townManagerText.townNotExist, "#" + id);
        }
        return town;
    }

    /**
     * Resolve an active town from the shared database when the local cache is
     * behind another backend. Callers must already be off the server thread.
     */
    public static @NotNull TownDOO requireTownFromSharedDatabase(@NotNull String name)
            throws DominionException {
        try {
            TownDOO town = TownDOO.selectAll().stream()
                    .filter(candidate -> candidate.getStatus() == TownDOO.TownStatus.ACTIVE)
                    .filter(candidate -> candidate.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
            if (town != null) return town;
        } catch (SQLException e) {
            XLogger.error("Failed to resolve town {0} from the shared database: {1}", name, e.getMessage());
        }
        throw new DominionException(Language.townManagerText.townNotExist, name);
    }

    public static @NotNull TownGroupDOO requireTownGroup(@NotNull TownDOO town,
                                                         @NotNull String name) throws DominionException {
        TownGroupDOO group = cache().getGroup(town.getId(), name);
        if (group == null) {
            throw new DominionException(Language.townManagerText.townGroupNotExist,
                    name, town.getName());
        }
        return group;
    }

    public static @NotNull List<TownGroupDOO> groupsOf(@NotNull TownDOO town) {
        return new ArrayList<>(cache().getGroupsOf(town.getId()));
    }

    private static @NotNull String normalizeTownGroupName(@NotNull String name) throws DominionException {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 32) {
            throw new DominionException(Language.townManagerText.townGroupNameTooLong);
        }
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_-]+")) {
            throw new DominionException(Language.townManagerText.townGroupNameInvalid);
        }
        return normalized;
    }

    public static @NotNull TownGroupDOO createTownGroup(@NotNull Player operator,
                                                         @NotNull TownDOO town,
                                                         @NotNull String name,
                                                         @Nullable String displayName) throws Exception {
        assertManager(operator, town);
        String normalized = normalizeTownGroupName(name);
        String display = displayName == null || displayName.isBlank() ? normalized : displayName.trim();
        if (display.length() > 64) {
            throw new DominionException(Language.townManagerText.townGroupDisplayTooLong);
        }
        if (TownGroupDOO.select(town.getId(), normalized) != null) {
            throw new DominionException(Language.townManagerText.townGroupExists,
                    normalized, town.getName());
        }
        TownGroupDOO group = TownGroupSyncHandler.createGroup(town, normalized, display);
        Notification.info(operator, Language.townManagerText.townGroupCreated,
                group.getDisplayName(), town.getName());
        return group;
    }

    public static void updateTownGroupDisplayName(@NotNull Player operator,
                                                   @NotNull TownDOO town,
                                                   @NotNull TownGroupDOO group,
                                                   @Nullable String displayName) throws Exception {
        assertManager(operator, town);
        if (group.getTownId() != town.getId()) {
            throw new DominionException(Language.townManagerText.townGroupNotExist,
                    group.getName(), town.getName());
        }
        String display = displayName == null || displayName.isBlank()
                || displayName.trim().equals("-") ? group.getName() : displayName.trim();
        if (display.length() > 64) {
            throw new DominionException(Language.townManagerText.townGroupDisplayTooLong);
        }
        String oldDisplay = group.getDisplayName();
        TownGroupSyncHandler.updateDisplayName(group, display);
        Notification.info(operator, Language.townManagerText.townGroupDisplayUpdated,
                oldDisplay, display);
    }

    public static void deleteTownGroup(@NotNull Player operator, @NotNull TownDOO town,
                                       @NotNull TownGroupDOO group) throws Exception {
        assertManager(operator, town);
        if (group.getTownId() != town.getId()) {
            throw new DominionException(Language.townManagerText.townGroupNotExist,
                    group.getName(), town.getName());
        }
        TownGroupSyncHandler.deleteGroup(group);
        Notification.info(operator, Language.townManagerText.townGroupDeleted,
                group.getDisplayName(), town.getName());
    }

    public static void assignTownGroup(@NotNull Player operator, @NotNull TownDOO town,
                                       @NotNull UUID target, @NotNull TownGroupDOO group) throws Exception {
        assertManager(operator, town);
        if (group.getTownId() != town.getId()) {
            throw new DominionException(Language.townManagerText.townGroupNotExist,
                    group.getName(), town.getName());
        }
        if (cache().getMember(town.getId(), target) == null) {
            throw new DominionException(Language.townManagerText.targetNotMember,
                    CacheManager.instance.getPlayerName(target), town.getName());
        }
        TownGroupSyncHandler.assignMember(group, target);
        Notification.info(operator, Language.townManagerText.townGroupMemberAssigned,
                CacheManager.instance.getPlayerName(target), group.getDisplayName());
    }

    public static void removeTownGroupAssignment(@NotNull Player operator, @NotNull TownDOO town,
                                                  @NotNull UUID target) throws Exception {
        assertManager(operator, town);
        if (cache().getMember(town.getId(), target) == null) {
            throw new DominionException(Language.townManagerText.targetNotMember,
                    CacheManager.instance.getPlayerName(target), town.getName());
        }
        TownGroupSyncHandler.removeMemberAssignment(town.getId(), target);
        Notification.info(operator, Language.townManagerText.townGroupMemberRemoved,
                CacheManager.instance.getPlayerName(target));
    }

    public static void assertTownName(@NotNull String name) throws DominionException, SQLException {
        if (name.isEmpty()) {
            throw new DominionException(Language.townManagerText.townNameShouldNotEmpty);
        }
        // same character set as assertDominionName: the town name becomes
        // the founding outpost's dominion name and is rendered into other
        // players' messages (legacy color codes would be an injection)
        if (name.contains(" ") || name.contains(".")
                || name.contains("§") || name.contains("&")
                || name.contains("~") || name.contains(",")) {
            throw new DominionException(Language.townManagerText.townNameInvalid);
        }
        // the cache resolves names case-insensitively and MySQL's default
        // collation is case-insensitive too, so uniqueness must be
        // case-insensitive everywhere or "Foo"/"foo" coexist on SQLite/PG
        // and one of them becomes unreachable by name
        if (cache().getTown(name) != null || TownDOO.select(name) != null) {
            throw new DominionException(Language.townManagerText.townNameExist, name);
        }
        for (TownDOO existing : TownDOO.selectAll()) {
            if (existing.getName().equalsIgnoreCase(name)) {
                throw new DominionException(Language.townManagerText.townNameExist, existing.getName());
            }
        }
    }

    private static void assertTownRenameName(@NotNull TownDOO town, @NotNull String name)
            throws DominionException, SQLException {
        if (name.isEmpty()) {
            throw new DominionException(Language.townManagerText.townNameShouldNotEmpty);
        }
        if (name.contains(" ") || name.contains(".")
                || name.contains("§") || name.contains("&")
                || name.contains("~") || name.contains(",")) {
            throw new DominionException(Language.townManagerText.townNameInvalid);
        }
        if (name.equals(town.getName())) {
            throw new DominionException(Language.townManagerText.townNameUnchanged, name);
        }
        TownDOO cached = cache().getTown(name);
        if (cached != null && !cached.getId().equals(town.getId())) {
            throw new DominionException(Language.townManagerText.townNameExist, cached.getName());
        }
        TownDOO selected = TownDOO.select(name);
        if (selected != null && !selected.getId().equals(town.getId())) {
            throw new DominionException(Language.townManagerText.townNameExist, selected.getName());
        }
        for (TownDOO existing : TownDOO.selectAll()) {
            if (!existing.getId().equals(town.getId()) && existing.getName().equalsIgnoreCase(name)) {
                throw new DominionException(Language.townManagerText.townNameExist, existing.getName());
            }
        }
    }

    /**
     * max-memberships gate for NEW formal memberships (create or join).
     * TownTrust and existing memberships are unaffected; -1 = unlimited.
     */
    public static void assertCanJoinNewTown(@NotNull Player player) throws DominionException, SQLException {
        if (bypassLimit(player)) return;
        int max = Configuration.town.maxMemberships;
        if (max == -1) return;
        int current = TownMemberDOO.countTownsOf(player.getUniqueId());
        if (current >= max) {
            throw new DominionException(Language.townManagerText.maxMembershipsReached, current, max);
        }
    }

    /** Role of a player in a town, or null when not a member. */
    public static TownMemberDOO.@Nullable TownRole roleOf(@NotNull TownDOO town, @NotNull UUID player) {
        TownMemberDOO member = cache().getMember(town.getId(), player);
        return member == null ? null : member.getRole();
    }

    /**
     * The player's "home" town for UI purposes: with multiple memberships,
     * prefer the one they actually lead (mayor over assistant over
     * resident), matching TownCommand resolution. Null when townless.
     */
    public static @Nullable TownDOO ownTownOf(@NotNull Player player) {
        List<TownMemberDOO> memberships = new ArrayList<>(
                cache().getMembershipsOf(player.getUniqueId()));
        memberships.sort(java.util.Comparator.comparingInt(m -> switch (m.getRole()) {
            case MAYOR -> 0;
            case ASSISTANT -> 1;
            case RESIDENT -> 2;
        }));
        for (TownMemberDOO membership : memberships) {
            TownDOO town = cache().getTown(membership.getTownId());
            if (town != null) return town;
        }
        return null;
    }

    public static boolean isManager(@NotNull TownDOO town, @NotNull UUID player) {
        TownMemberDOO member = cache().getMember(town.getId(), player);
        return member != null && (member.getRole() == TownMemberDOO.TownRole.MAYOR
                || member.getRole() == TownMemberDOO.TownRole.ASSISTANT);
    }

    public static void assertManager(@NotNull CommandSender sender, @NotNull TownDOO town) throws DominionException {
        if (!(sender instanceof Player player)) return; // console is always allowed
        if (bypassLimit(player)) return;
        if (!isManager(town, player.getUniqueId())) {
            throw new DominionException(Language.townManagerText.notTownManager, town.getName());
        }
    }

    /** Disbanding is restricted to the mayor; admins and console may arbitrate. */
    public static void assertCanDisband(@NotNull CommandSender sender,
                                        @NotNull TownDOO town) throws DominionException {
        if (!(sender instanceof Player player)) return;
        if (bypassLimit(player)) return;
        if (!town.getMayorUuid().equals(player.getUniqueId())) {
            throw new DominionException(Language.townManagerText.onlyMayorCanDisband, town.getName());
        }
    }

    /** Renaming is a town identity change and is restricted to the mayor. */
    public static void assertCanRename(@NotNull CommandSender sender,
                                       @NotNull TownDOO town) throws DominionException {
        if (!(sender instanceof Player player)) return;
        if (bypassLimit(player)) return;
        if (!town.getMayorUuid().equals(player.getUniqueId())) {
            throw new DominionException(Language.townManagerText.onlyMayorCanRename, town.getName());
        }
    }

    /**
     * Rename a town in the shared database. All memberships, groups,
     * permissions and outposts remain attached by town_id; only the mutable
     * display/lookup name changes. The local replica is refreshed immediately
     * and other backends converge through their normal TownCache refresh.
     */
    public static @NotNull TownDOO renameTown(@NotNull CommandSender operator,
                                              @NotNull TownDOO town,
                                              @NotNull String requestedName) throws Exception {
        assertCanRename(operator, town);
        String newName = requestedName.trim();
        assertTownRenameName(town, newName);
        String oldName = town.getName();
        try {
            town.setName(newName);
        } catch (SQLException e) {
            // A concurrent rename on another backend may win between the
            // validation query and UPDATE; translate a resulting collision
            // into the normal user-facing duplicate-name error.
            TownDOO conflicting = TownDOO.select(newName);
            if (conflicting != null && !conflicting.getId().equals(town.getId())) {
                throw new DominionException(Language.townManagerText.townNameExist,
                        conflicting.getName());
            }
            throw e;
        }
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.renameSuccess, oldName, newName);
        return cache().getTown(town.getId()) != null ? cache().getTown(town.getId()) : town;
    }

    /**
     * One-way destructive migration requested explicitly by the mayor.
     * Mirrors are healed first while the town is still LEGACY; the actual
     * reset and mode flip then happen atomically in TownPermissionManager.
     */
    public static void upgradePermissionMode(@NotNull Player operator,
                                             @NotNull TownDOO town) throws Exception {
        boolean bypass = bypassLimit(operator);
        TownPermissionManager.assertUpgradeAllowed(
                town, operator.getUniqueId(), bypass);
        for (DominionDOO outpost : outpostsOf(town.getId())) {
            TownGroupSyncHandler.syncOutpost(town, outpost);
        }
        int outposts = TownPermissionManager.upgrade(
                town, operator.getUniqueId(), bypass);
        cache().reloadNow();
        TownGroupSyncHandler.reloadCaches();
        XLogger.info("Town {0} ({1}) upgraded to TEMPLATE_V1; reset {2} outpost(s) to inheritance.",
                town.getName(), town.getId(), outposts);
        Notification.info(operator, Language.townManagerText.templateModeUpgradeSuccess,
                town.getName(), outposts);
    }

    public static int townTemplateGroupKey(@NotNull TownDOO town,
                                           @NotNull String groupName) throws DominionException {
        if (TOWN_MEMBER_GROUP_NAME.equalsIgnoreCase(groupName)) {
            return TownPermissionManager.TOWN_DEFAULT_GROUP_ID;
        }
        return requireTownGroup(town, groupName).getId();
    }

    public static void setTownTemplateFlag(@NotNull Player operator, @NotNull TownDOO town,
                                           @NotNull String groupName, @NotNull PriFlag flag,
                                           boolean value) throws Exception {
        assertManager(operator, town);
        int groupKey = townTemplateGroupKey(town, groupName);
        TownPermissionManager.setTemplateFlag(town, groupKey, flag, value);
        Notification.info(operator, Language.townManagerText.templateFlagUpdated,
                groupName, flag.getDisplayName(), value);
    }

    /** Route an existing Boolean group write to a sparse local override. */
    public static @NotNull GroupDTO setTownOutpostOverride(@NotNull DominionDOO outpost,
                                                           @NotNull GroupDTO group,
                                                           @NotNull PriFlag flag,
                                                           boolean value) throws Exception {
        if (!isTownManagedGroup(group, outpost)) {
            return group.setFlagValue(flag, value);
        }
        TownPermissionManager.setOutpostFlag(outpost, group, flag, value);
        GroupDOO refreshed = GroupDOO.select(group.getId());
        return refreshed == null ? group : refreshed;
    }

    public static void clearTownOutpostOverride(@NotNull Player operator,
                                                @NotNull DominionDOO outpost,
                                                @NotNull GroupDTO group,
                                                @NotNull PriFlag flag) throws Exception {
        cn.lunadeer.dominion.misc.Asserts.assertDominionAdmin(operator, outpost);
        if (!isTownManagedGroup(group, outpost)) {
            throw new DominionException(Language.townManagerText.templateModeRequired, "?");
        }
        TownPermissionManager.clearOutpostOverride(outpost, group, flag);
        Notification.info(operator, Language.townManagerText.outpostOverrideCleared,
                flag.getDisplayName(), groupDisplayName(group));
    }

    /**
     * Town-cascade management check for a town outpost: mayor/assistant,
     * the appointed outpost admin (plan §2.4), or the recorded owner. The
     * owner of a contributed dominion is the CONTRIBUTOR — contributing
     * land is not a donation of control, so they keep a special management
     * tier on their own outpost (flags, env, tp point). Operations that
     * spend town money or affect the town as a whole (resize, release)
     * stay mayor/admin-only.
     */
    public static boolean isOutpostManager(@NotNull Player player, @NotNull DominionDOO dominion) {
        Integer townId = dominion.getTownId();
        if (townId == null) return false;
        TownDOO town = cache().getTown(townId);
        if (town == null) return false;
        if (isManager(town, player.getUniqueId())) return true;
        if (player.getUniqueId().equals(dominion.getOwner())) return true;
        UUID outpostAdmin = dominion.getOutpostAdminUuid();
        return player.getUniqueId().equals(outpostAdmin);
    }

    // ------------------------------------------------------------------
    // Town lifecycle
    // ------------------------------------------------------------------

    /**
     * Create a town. Existing personal dominions remain personal and can be
     * contributed later; when the founder owns nothing, a founding outpost
     * is created at the given cuboid (required in that case). All validations
     * that can fail run BEFORE any row is inserted, so a failure never leaves
     * an orphan town behind.
     */
    public static @NotNull TownDOO createTown(@NotNull Player founder, @NotNull String name,
                                              @Nullable CuboidDTO foundingCuboid) throws Exception {
        World foundingWorld = founder.getWorld();
        assertCreationAllowed(founder, foundingWorld);
        assertTownName(name);
        assertCanJoinNewTown(founder);

        List<DominionDOO> owned = ownedDominions(founder.getUniqueId());

        // validate the founding outpost up front (town row does not exist yet)
        DominionDOO foundingToCreate = null;
        if (owned.isEmpty()) {
            if (foundingCuboid == null) {
                throw new DominionException(Language.townManagerText.foundingRequired);
            }
            // The founding outpost is a literal town.foundingSize cube
            // centered on the founder (baked into the auto cuboid) — no
            // size cap check and no personal-limitation check: town land
            // answers to town knobs (foundingSize, foundingCost), and
            // expansion afterwards follows normal town-outpost resize.
            foundingToCreate = new DominionDOO(founder.getUniqueId(), uniqueDominionName(name),
                    foundingWorld.getUID(), foundingCuboid, -1);
            assertDominionIntersect(founder, foundingToCreate, foundingCuboid);
            double cost = Configuration.town.foundingCost;
            if (cost > 0 && !bypassLimit(founder)
                    && VaultConnect.instance.getBalance(founder) < cost) {
                // the town bank does not exist yet; this is the founder's
                // personal wallet falling short
                throw new DominionException(Language.vaultConnectText.insufficientFunds,
                        cost, VaultConnect.instance.currencyNamePlural(),
                        VaultConnect.instance.getBalance(founder));
            }
        }

        TownDOO town = TownDOO.insert(name, founder.getUniqueId());
        cache().reloadNow();

        DominionDOO foundingCreated = null;
        try {
            TownPermissionManager.initializeNewTown(town);
            TownMemberDOO.insert(town.getId(), founder.getUniqueId(),
                    TownMemberDOO.TownRole.MAYOR, TownMemberDOO.MemberSource.FOUNDER);
            if (foundingToCreate != null) {
                foundingCreated = DominionDOO.insert(foundingToCreate);
                foundingCreated.setClaimKind("FOUNDING");
                // Publish the town link only after the immutable founding
                // marker is present, so a concurrent release can never see
                // this row as a releasable NORMAL outpost.
                foundingCreated.bornIntoTown(town.getId());
                TownGroupSyncHandler.syncOutpost(town, foundingCreated);
                // third-party visibility: town-driven creation bypasses the
                // provider pipeline, so fire the lifecycle event explicitly
                // (notification only — town operations are not vetoable)
                cn.lunadeer.dominion.events.dominion.DominionCreateEvent createEvent =
                        new cn.lunadeer.dominion.events.dominion.DominionCreateEvent(
                                founder, foundingCreated.getName(), founder.getUniqueId(),
                                foundingWorld, foundingCuboid, null);
                createEvent.setSkipEconomy(true);
                callLifecycleEvent(createEvent);
                // like the provider pipeline, complete the future so
                // third-party afterCreated callbacks fire
                createEvent.getFutureToComplete().complete(foundingCreated);
                double cost = Configuration.town.foundingCost;
                if (cost > 0 && !bypassLimit(founder)) {
                    // the town bank starts empty, so the founding cost is
                    // charged to the founder's wallet (free by default, §12.1)
                    VaultConnect.instance.withdrawPlayer(founder, cost);
                }
                Notification.info(founder, Language.townManagerText.createSuccessFounding, name);
            } else {
                // No auto-attach: player manually selects dominions via /town attach (phase 2)
                Notification.info(founder, Language.townManagerText.createSuccess, name);
                // Show hint if player has attachable personal dominions
                int attachableCount = (int) ownedDominions(founder.getUniqueId()).stream()
                        .filter(d -> !d.isTownOutpost()).count();
                if (attachableCount > 0) {
                    Notification.info(founder, Language.townManagerText.attachHint, attachableCount);
                }
            }
        } catch (Exception e) {
            // a failure after the town row was inserted must not leave
            // orphans behind — roll every insert back before rethrowing:
            // the founding dominion (if any) and the town rows themselves.
            // Member rows consumed by merge are preserved in
            // dominion_member_archive for manual recovery.
            XLogger.error("createTown failed after town insert (town={0}); rolling back. {1}",
                    town.getId(), e.getMessage());
            try {
                if (foundingCreated != null) {
                    DominionDOO.deleteById(foundingCreated.getId());
                }
                for (DominionDOO attachedDom : DominionDOO.selectByTownId(town.getId())) {
                    attachedDom.detachFromTown();
                }
                TownMemberDOO.deleteByTown(town.getId());
                TownTrustDOO.deleteByTown(town.getId());
                TownInviteManager.deleteByTown(town.getId());
                TownDOO.deleteById(town.getId());
                cache().reloadNow();
            } catch (Exception rollbackError) {
                XLogger.error("Rollback of failed createTown ALSO failed (town={0}): {1}",
                        town.getId(), rollbackError.getMessage());
            }
            throw e;
        }
        cache().reloadNow();
        return town;
    }

    /**
     * Disband a town. dissolve-all: every outpost is archived then deleted
     * (protection data only — blocks are never touched). return-contributed:
     * legacy-attached outposts (attached_by != null) detach back to their
     * contributor; town-native outposts (founding/remote, attached_by NULL)
     * dissolve. Members and trusts cascade-delete with the town row.
     */
    public static void disbandTown(@NotNull CommandSender operator, @NotNull TownDOO town) throws Exception {
        assertCanDisband(operator, town);
        boolean dissolveAll = !"return-contributed".equals(Configuration.town.disbandPolicy);
        List<DominionDOO> outposts = outpostsOf(town.getId());
        int dissolved = 0;
        int returned = 0;
        // ids already handled as part of a parent's sub-tree: deleting a
        // parent FK-cascades its subs, so subs must be archived and
        // deleted BEFORE their parent or their archive rows are lost
        java.util.Set<Integer> processed = new java.util.HashSet<>();
        for (DominionDOO outpost : outposts) {
            if (!processed.add(outpost.getId())) continue;
            boolean contributed = outpost.getAttachedBy() != null;
            if (dissolveAll || !contributed) {
                // Sub-dominions inside the dissolving outpost: enumerated
                // from the DATABASE (not the per-server cache) so subs
                // created on other backends are never cascade-deleted
                // without an archive row. Contributed subs (attached_by
                // set) are reparented to root and skipped — the outer loop
                // returns them to their contributor; everything else
                // (foreign enclaves, town-native subs) dissolves with the
                // parent, deepest first.
                for (DominionDOO sub : outpost.selectSubTreeDeepestFirst()) {
                    if (sub.isTownOutpost() && sub.getAttachedBy() != null) {
                        // contributed sub: detach it from the dissolving
                        // parent but do NOT mark it processed — if it
                        // belongs to THIS town the outer loop still has to
                        // handle it per policy (dissolve-all archives and
                        // deletes it; return-contributed returns it).
                        // Cross-town enclave subs simply survive at root.
                        sub.reparentToRoot();
                        continue;
                    }
                    if (!processed.add(sub.getId())) continue;
                    UUID enclaveOwner = sub.getOwner();
                    archiveDominion(sub, town.getId(), "DISBANDED_TOWN");
                    archiveMembers(sub, town.getId(), "DISBANDED_TOWN");
                    cn.lunadeer.dominion.events.dominion.DominionDeleteEvent subDeleteEvent =
                            new cn.lunadeer.dominion.events.dominion.DominionDeleteEvent(operator, sub);
                    subDeleteEvent.setSkipEconomy(true);
                    callLifecycleEvent(subDeleteEvent); // notification only; town disband is not vetoable
                    DominionDOO.deleteById(sub.getId());
                    notifyIfOnline(enclaveOwner, Language.townManagerText.disbandEnclaveDissolved,
                            sub.getName(), town.getName());
                }
                archiveDominion(outpost, town.getId(), "DISBANDED_TOWN");
                archiveMembers(outpost, town.getId(), "DISBANDED_TOWN");
                cn.lunadeer.dominion.events.dominion.DominionDeleteEvent deleteEvent =
                        new cn.lunadeer.dominion.events.dominion.DominionDeleteEvent(operator, outpost);
                deleteEvent.setSkipEconomy(true);
                callLifecycleEvent(deleteEvent); // notification only
                DominionDOO.deleteById(outpost.getId());
                dissolved++;
            } else {
                TownGroupSyncHandler.releaseOutpost(outpost.getId());
                returned++;
                Player contributor = operator.getServer().getPlayer(outpost.getOwner());
                if (contributor != null) {
                    Notification.info(contributor, Language.townManagerText.releaseSuccess,
                            outpost.getName(), town.getName(), CacheManager.instance.getPlayerName(outpost.getOwner()));
                }
            }
        }
        TownMemberDOO.deleteByTown(town.getId());
        TownTrustDOO.deleteByTown(town.getId());
        TownInviteManager.deleteByTown(town.getId());
        TownDOO.deleteById(town.getId());
        cache().reloadNow();
        CacheManager.instance.getCache().getDominionCache().load();
        CacheManager.instance.getCache().getMemberCache().load();
        // dissolved outposts' @town groups die with them via FK cascade
        // (or orphan on SQLite); don't leave phantoms in the group cache
        CacheManager.instance.getCache().getGroupCache().load();
        if (dissolveAll) {
            Notification.info(operator, Language.townManagerText.disbandSuccess, town.getName(), dissolved);
        } else {
            Notification.info(operator, Language.townManagerText.disbandSuccessReturn, town.getName(), returned, dissolved);
        }
    }

    /**
     * Join a town after confirmation. Personal dominions remain personal;
     * selected land can be attached separately through /town attach. The
     * player becomes a RESIDENT with source JOINED.
     */
    public static void joinTown(@NotNull Player player, @NotNull TownDOO town) throws Exception {
        joinTown(player, town, true);
    }

    /**
     * Join a town atomically with invitation consumption. The invitation is
     * deleted and the membership inserted on one connection so concurrent
     * acceptance cannot consume the same invite twice or lose it on failure.
     */
    public static void joinTown(@NotNull Player player, @NotNull TownDOO town,
                                boolean requireInvite) throws Exception {
        if (cache().getMember(town.getId(), player.getUniqueId()) != null) {
            throw new DominionException(Language.townManagerText.alreadyInTown, town.getName());
        }
        assertCanJoinNewTown(player);
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement townCheck = connection.prepareStatement(
                        "SELECT 1 FROM town WHERE id = ? AND status <> 'DISBANDED'")) {
                    townCheck.setInt(1, town.getId());
                    try (ResultSet rows = townCheck.executeQuery()) {
                        if (!rows.next()) {
                            throw new DominionException(Language.townManagerText.townNotExist, town.getName());
                        }
                    }
                }
                try (PreparedStatement memberCheck = connection.prepareStatement(
                        "SELECT 1 FROM town_member WHERE town_id = ? AND player_uuid = ?")) {
                    memberCheck.setInt(1, town.getId());
                    memberCheck.setString(2, player.getUniqueId().toString());
                    try (ResultSet rows = memberCheck.executeQuery()) {
                        if (rows.next()) {
                            throw new DominionException(Language.townManagerText.alreadyInTown, town.getName());
                        }
                    }
                }
                if (requireInvite && !TownInviteManager.consumeInvite(
                        connection, town.getId(), player.getUniqueId())) {
                    throw new DominionException(Language.townCommandText.noInvite, town.getName());
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO town_member(town_id, player_uuid, role, source, group_id) VALUES (?, ?, 'RESIDENT', 'JOINED', -1)")) {
                    insert.setInt(1, town.getId());
                    insert.setString(2, player.getUniqueId().toString());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
        TownInviteManager.clearNotified(player.getUniqueId(), town.getId());
        // join raced with a concurrent disband: attaching now would point
        // this player's land at a dead town and strip their access, so
        // re-verify the town row and roll the membership back
        if (TownDOO.select(town.getId()) == null) {
            TownMemberDOO.delete(town.getId(), player.getUniqueId());
            cache().reloadNow();
            throw new DominionException(Language.townManagerText.townNotExist, town.getName());
        }
        cache().reloadNow();
        Notification.info(player, Language.townManagerText.joinSuccess, town.getName());
        // Show hint if player has attachable personal dominions
        int attachableCount = (int) ownedDominions(player.getUniqueId()).stream()
                .filter(d -> !d.isTownOutpost()).count();
        if (attachableCount > 0) {
            Notification.info(player, Language.townManagerText.attachHint, attachableCount);
        }
    }

    // ===== Role and Source Display Helpers =====

    /**
     * Get the localized display name for a town role.
     */
    public static @NotNull String roleDisplay(@NotNull TownMemberDOO.TownRole role) {
        return switch (role) {
            case MAYOR -> Language.townRoleText.mayor;
            case ASSISTANT -> Language.townRoleText.assistant;
            case RESIDENT -> Language.townRoleText.resident;
        };
    }

    /**
     * Get the localized display name for a member source.
     */
    public static @NotNull String sourceDisplay(@NotNull TownMemberDOO.MemberSource source) {
        return switch (source) {
            case FOUNDER -> Language.townRoleText.founder;
            case JOINED -> Language.townRoleText.joined;
            case MERGED -> Language.townRoleText.merged;
        };
    }

    /**
     * Leave a town. Land stays (town property); outpost-admin appointments
     * held by the player are cleared (plan §2.4).
     */
    public static void leaveTown(@NotNull Player player, @NotNull TownDOO town) throws Exception {
        TownMemberDOO member = cache().getMember(town.getId(), player.getUniqueId());
        if (member == null) {
            throw new DominionException(Language.townManagerText.notInTown, town.getName());
        }
        if (member.getRole() == TownMemberDOO.TownRole.MAYOR && !bypassLimit(player)) {
            throw new DominionException(Language.townManagerText.mayorCannotLeave);
        }
        TownGroupSyncHandler.removeDepartedMember(town.getId(), player.getUniqueId());
        TownMemberDOO.delete(town.getId(), player.getUniqueId());
        clearOutpostAdmin(town.getId(), player.getUniqueId());
        cache().reloadNow();
        Notification.info(player, Language.townManagerText.leaveSuccess, town.getName());
    }

    public static void kickMember(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target) throws Exception {
        assertManager(operator, town);
        TownMemberDOO member = cache().getMember(town.getId(), target);
        if (member == null) {
            throw new DominionException(Language.townManagerText.targetNotMember,
                    CacheManager.instance.getPlayerName(target), town.getName());
        }
        if (member.getRole() != TownMemberDOO.TownRole.RESIDENT && !bypassLimit(operator)) {
            throw new DominionException(Language.townManagerText.cannotKickManager);
        }
        TownGroupSyncHandler.removeDepartedMember(town.getId(), target);
        TownMemberDOO.delete(town.getId(), target);
        clearOutpostAdmin(town.getId(), target);
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.kickSuccess,
                CacheManager.instance.getPlayerName(target), town.getName());
        Player targetPlayer = operator.getServer().getPlayer(target);
        if (targetPlayer != null) {
            Notification.info(targetPlayer, Language.townManagerText.leaveSuccess, town.getName());
        }
    }

    /**
     * Promote a resident to assistant / demote an assistant to resident.
     * Mayor only; the mayor role itself changes hands solely via
     * {@link #transferMayor}.
     */
    public static void setRole(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target,
                               @NotNull TownMemberDOO.TownRole newRole) throws Exception {
        // Only the mayor (or bypass admin) can promote/demote
        if (!bypassLimit(operator) && !town.getMayorUuid().equals(operator.getUniqueId())) {
            throw new DominionException(Language.townManagerText.onlyMayorCanSetRole);
        }
        if (newRole == TownMemberDOO.TownRole.MAYOR) {
            throw new DominionException(Language.townManagerText.useTransferForMayor);
        }
        TownMemberDOO member = cache().getMember(town.getId(), target);
        if (member == null) {
            throw new DominionException(Language.townManagerText.targetNotMember,
                    CacheManager.instance.getPlayerName(target), town.getName());
        }
        // the mayor's membership row must never be demoted — not even by
        // admins: town.mayor_uuid would dangle at a non-mayor member and
        // the town becomes unmanageable. Mayor changes hands solely via
        // transferMayor (which updates mayor_uuid atomically).
        if (member.getRole() == TownMemberDOO.TownRole.MAYOR) {
            throw new DominionException(Language.townManagerText.useTransferForMayor);
        }
        if (member.getRole() == newRole) {
            return;
        }
        member.setRole(newRole);
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.setRoleSuccess,
                CacheManager.instance.getPlayerName(target), roleDisplay(newRole), town.getName());
        notifyIfOnline(target, Language.townManagerText.setRoleNotify, roleDisplay(newRole), town.getName());
    }

    /**
     * Transfer the mayor role: pure town_member/town.mayor_uuid change, zero
     * dominion row writes (plan §3). Only the current mayor (or an admin)
     * may transfer — assistants must not self-promote. The old mayor
     * becomes an assistant.
     */
    public static void transferMayor(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target) throws Exception {
        if (!bypassLimit(operator) && !town.getMayorUuid().equals(operator.getUniqueId())) {
            throw new DominionException(Language.townManagerText.onlyMayorCanTransfer, town.getName());
        }
        TownMemberDOO targetMember = cache().getMember(town.getId(), target);
        if (targetMember == null) {
            throw new DominionException(Language.townManagerText.targetNotMember,
                    CacheManager.instance.getPlayerName(target), town.getName());
        }
        TownMemberDOO oldMayorMember = cache().getMember(town.getId(), town.getMayorUuid());
        targetMember.setRole(TownMemberDOO.TownRole.MAYOR);
        if (oldMayorMember != null && !oldMayorMember.getPlayerUuid().equals(target)) {
            oldMayorMember.setRole(TownMemberDOO.TownRole.ASSISTANT);
        }
        town.setMayor(target);
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.transferSuccess,
                town.getName(), CacheManager.instance.getPlayerName(target));
    }

    // ------------------------------------------------------------------
    // Trust
    // ------------------------------------------------------------------

    /**
     * Trust authorization: TOWN scope requires mayor/assistant; OUTPOST
     * scope additionally allows that outpost's appointed admin (plan §2.4:
     * the outpost admin manages the outpost's member overrides and Trust).
     */
    private static void assertTrustOperator(@NotNull Player operator, @NotNull TownDOO town,
                                            @NotNull TownTrustDOO.TrustScope scope,
                                            @Nullable DominionDOO outpost) throws DominionException {
        if (bypassLimit(operator)) return;
        if (scope == TownTrustDOO.TrustScope.OUTPOST && outpost != null
                && isOutpostManager(operator, outpost)) {
            // any outpost manager (mayor/assistant, the contributor, the
            // appointed outpost admin) manages this outpost's trusts
            return;
        }
        assertManager(operator, town);
    }

    public static void trust(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target,
                             @NotNull TownTrustDOO.TrustScope scope, @Nullable DominionDOO outpost,
                             boolean build, boolean destroy, boolean switchFlag, boolean itemUse) throws Exception {
        assertTrustOperator(operator, town, scope, outpost);
        if (!Configuration.town.trustEnabled) {
            throw new DominionException(Language.townManagerText.trustDisabled);
        }
        int scopeId;
        if (scope == TownTrustDOO.TrustScope.TOWN) {
            scopeId = town.getId();
        } else {
            if (outpost == null || !town.getId().equals(outpost.getTownId())) {
                throw new DominionException(Language.townManagerText.trustTargetOutpostNotInTown,
                        outpost == null ? "?" : outpost.getName(), town.getName());
            }
            scopeId = outpost.getId();
        }
        // upsert: the composite unique index allows exactly one row per
        // (town, player, scope_type, scope_id)
        TownTrustDOO.delete(town.getId(), target, scope, scopeId);
        TownTrustDOO.insert(town.getId(), target, scope, scopeId, build, destroy, switchFlag, itemUse,
                operator.getUniqueId());
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.trustSuccess,
                CacheManager.instance.getPlayerName(target), town.getName(), scope.name());
    }

    public static void untrust(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target,
                               @NotNull TownTrustDOO.TrustScope scope, @Nullable DominionDOO outpost) throws Exception {
        assertTrustOperator(operator, town, scope, outpost);
        int scopeId = scope == TownTrustDOO.TrustScope.TOWN ? town.getId()
                : (outpost == null ? -1 : outpost.getId());
        if (scope == TownTrustDOO.TrustScope.OUTPOST) {
            // never fake success: an unresolvable outpost must not turn
            // into a silent no-op DELETE while the grant survives
            if (outpost == null || !town.getId().equals(outpost.getTownId())) {
                throw new DominionException(Language.townManagerText.trustTargetOutpostNotInTown,
                        outpost == null ? "?" : outpost.getName(), town.getName());
            }
        }
        TownTrustDOO.delete(town.getId(), target, scope, scopeId);
        cache().reloadNow();
        Notification.info(operator, Language.townManagerText.untrustSuccess,
                CacheManager.instance.getPlayerName(target), town.getName(), scope.name());
    }

    // ------------------------------------------------------------------
    // Town bank
    // ------------------------------------------------------------------

    /**
     * Quantize a currency amount to whole cents. Sub-cent amounts are
     * rejected: the town bank stores integer cents, so a 0.001 deposit
     * would otherwise round to zero and silently print/destroy money.
     */
    private static long toCents(double amount) throws DominionException {
        if (!(amount > 0) || !Double.isFinite(amount)) {
            throw new DominionException(Language.townManagerText.invalidAmount, amount);
        }
        long cents = Math.round(amount * 100.0);
        if (cents <= 0) {
            throw new DominionException(Language.townManagerText.invalidAmount, amount);
        }
        return cents;
    }

    public static void deposit(@NotNull Player player, @NotNull TownDOO town, double amount) throws Exception {
        long cents = toCents(amount);
        double quantized = cents / 100.0;
        if (cache().getMember(town.getId(), player.getUniqueId()) == null && !bypassLimit(player)) {
            throw new DominionException(Language.townManagerText.onlyMemberCanDeposit, town.getName());
        }
        VaultConnect.instance.withdrawPlayer(player, quantized);
        try {
            town.adjustBalance(quantized);
        } catch (Exception e) {
            // second leg failed: refund the player and leave a paper trail
            XLogger.error("Town deposit second leg failed (player={0}, town={1}, amount={2}); refunding. {3}",
                    player.getUniqueId(), town.getId(), quantized, e.getMessage());
            try {
                VaultConnect.instance.depositPlayer(player, quantized);
            } catch (Exception refundError) {
                XLogger.error("Refund after failed town deposit ALSO failed (player={0}, amount={1}): {2}",
                        player.getUniqueId(), quantized, refundError.getMessage());
            }
            throw e;
        }
        cache().reloadNow();
        Notification.info(player, Language.townManagerText.depositSuccess,
                quantized, town.getName(), town.getBalance());
    }

    public static void withdraw(@NotNull Player player, @NotNull TownDOO town, double amount) throws Exception {
        long cents = toCents(amount);
        double quantized = cents / 100.0;
        assertManager(player, town);
        if (bypassLimit(player)) {
            town.adjustBalance(-quantized);
        } else if (!town.tryChargeCents(cents)) {
            // atomic guarded charge: a concurrent withdrawal can never
            // overdraw the bank — the loser lands here
            throw new DominionException(Language.townManagerText.insufficientTownFunds,
                    town.getName(), quantized, town.getBalance());
        }
        try {
            VaultConnect.instance.depositPlayer(player, quantized);
        } catch (Exception e) {
            // second leg failed: put the money back into the town bank
            XLogger.error("Town withdraw second leg failed (player={0}, town={1}, amount={2}); restoring bank. {3}",
                    player.getUniqueId(), town.getId(), quantized, e.getMessage());
            try {
                town.adjustBalance(quantized);
            } catch (Exception restoreError) {
                XLogger.error("Restore after failed town withdraw ALSO failed (town={0}, amount={1}): {2}",
                        town.getId(), quantized, restoreError.getMessage());
            }
            throw e;
        }
        cache().reloadNow();
        Notification.info(player, Language.townManagerText.withdrawSuccess,
                quantized, town.getName(), town.getBalance());
    }

    // ------------------------------------------------------------------
    // Town expansion (plan §4.6)
    // ------------------------------------------------------------------

    /**
     * Create a remote town outpost. Horizontal size and economy are town-level
     * policy; vertical/world bounds and minimum dimensions remain compatible
     * with the operator's limitation file.
     */
    public static @NotNull DominionDOO createRemoteOutpost(@NotNull Player operator, @NotNull TownDOO town,
                                                           @NotNull String dominionName, @NotNull World world,
                                                           @NotNull CuboidDTO cuboid) throws Exception {
        assertManager(operator, town);
        assertCreationAllowed(operator, world);
        if (!Configuration.town.outpostAllowRemote) {
            throw new DominionException(Language.townManagerText.outpostRemoteDisabled);
        }
        assertDominionName(dominionName);
        assertTownOutpostSize(world.getUID(), cuboid);

        DominionDOO toCreate = new DominionDOO(operator.getUniqueId(), dominionName, world.getUID(), cuboid, -1);
        assertDominionIntersect(operator, toCreate, cuboid);

        long quotedCents = townOutpostPriceCents(CuboidDTO.ZERO, cuboid);
        long cents = bypassLimit(operator) ? 0 : quotedCents;
        double price = cents / 100.0;
        DominionDOO created = TownGroupSyncHandler.createRemoteOutpost(
                town, operator.getUniqueId(), dominionName, world.getUID(), cuboid, cents);

        // The database transaction is already committed. This event exposes
        // the completed result to integrations, but cannot veto or roll back
        // town land after it has become durable.
        cn.lunadeer.dominion.events.dominion.DominionCreateEvent createEvent =
                new cn.lunadeer.dominion.events.dominion.DominionCreateEvent(
                        operator, dominionName, operator.getUniqueId(), world, cuboid, null);
        createEvent.setSkipEconomy(true);
        try {
            callLifecycleEvent(createEvent);
        } catch (Exception e) {
            XLogger.error("DominionCreateEvent failed after remote outpost commit "
                            + "(town={0}, dominion={1}): {2}",
                    town.getId(), created.getId(), e.getMessage());
        }
        createEvent.getFutureToComplete().complete(created);
        Notification.info(operator, Language.townManagerText.outpostCreateSuccess,
                dominionName, town.getName(), Math.max(price, 0),
                VaultConnect.instance.currencyNamePluralOrEmpty());
        return created;
    }

    /**
     * Atomically resize a town outpost and update its bank/paid-value ledger.
     * The guarded SQL writes share one transaction, so a concurrent resize or
     * insufficient balance cannot leave either land or money half-updated.
     */
    public static @NotNull DominionDOO resizeTownOutpost(@Nullable Player operator,
                                                         @NotNull TownDOO town,
                                                         @NotNull DominionDOO outpost,
                                                         @NotNull CuboidDTO before,
                                                         @NotNull CuboidDTO after,
                                                         boolean skipEconomy) throws Exception {
        long paidBefore = outpost.getTownPaidValueCents();
        boolean footprintChanged = before.getSquare() != after.getSquare();
        long paidAfter = paidBefore;
        long bankDeltaCents = 0;
        if (footprintChanged && (skipEconomy || operator == null || bypassLimit(operator)
                || !Configuration.town.outpostPricing.enable)) {
            // Once land is changed for free, the scalar ledger can no longer
            // prove which part was paid. Drop it instead of allowing a later
            // contraction to refund value that may have been free.
            paidAfter = 0;
        } else {
            long grossDeltaCents = townOutpostPriceCents(before, after);
            if (grossDeltaCents > 0) {
                paidAfter = Math.min(Math.addExact(paidBefore, grossDeltaCents),
                        townOutpostValueCents(after.getSquare()));
                bankDeltaCents = -grossDeltaCents;
            } else if (grossDeltaCents < 0) {
                long removedGrossCents = -grossDeltaCents;
                long refundableGrossCents = Math.min(paidBefore, removedGrossCents);
                paidAfter = Math.min(paidBefore - refundableGrossCents,
                        townOutpostValueCents(after.getSquare()));
                bankDeltaCents = Math.round(
                        refundableGrossCents * Configuration.town.outpostPricing.refundRate);
            } else if (footprintChanged && paidBefore > 0) {
                paidAfter = 0;
            }
        }

        try (Connection conn = DatabaseManager.instance.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (bankDeltaCents < 0) {
                    long chargeCents = -bankDeltaCents;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE town SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
                        ps.setLong(1, chargeCents);
                        ps.setInt(2, town.getId());
                        ps.setLong(3, chargeCents);
                        if (ps.executeUpdate() != 1) {
                            throw new DominionException(Language.townManagerText.insufficientTownFunds,
                                    town.getName(), chargeCents / 100.0, town.getBalance());
                        }
                    }
                } else if (bankDeltaCents > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE town SET balance = balance + ? WHERE id = ?")) {
                        ps.setLong(1, bankDeltaCents);
                        ps.setInt(2, town.getId());
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("Town " + town.getId() + " no longer exists.");
                        }
                    }
                }

                String resizeSql = "UPDATE dominion SET x1 = ?, y1 = ?, z1 = ?, x2 = ?, y2 = ?, z2 = ?, "
                        + "town_paid_value = ? WHERE id = ? AND x1 = ? AND y1 = ? AND z1 = ? "
                        + "AND x2 = ? AND y2 = ? AND z2 = ? AND town_paid_value = ?";
                try (PreparedStatement ps = conn.prepareStatement(resizeSql)) {
                    ps.setInt(1, after.x1());
                    ps.setInt(2, after.y1());
                    ps.setInt(3, after.z1());
                    ps.setInt(4, after.x2());
                    ps.setInt(5, after.y2());
                    ps.setInt(6, after.z2());
                    ps.setLong(7, paidAfter);
                    ps.setInt(8, outpost.getId());
                    ps.setInt(9, before.x1());
                    ps.setInt(10, before.y1());
                    ps.setInt(11, before.z1());
                    ps.setInt(12, before.x2());
                    ps.setInt(13, before.y2());
                    ps.setInt(14, before.z2());
                    ps.setLong(15, paidBefore);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("Dominion " + outpost.getId()
                                + " changed concurrently; retry the resize.");
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        cache().reloadNow();
        CacheManager.instance.getCache().getDominionCache().load(outpost.getId());
        DominionDOO updated = DominionDOO.select(outpost.getId());
        if (updated == null) {
            throw new SQLException("Dominion " + outpost.getId() + " disappeared after resize.");
        }
        return updated;
    }

    /**
     * Difference between the cumulative values of two outpost footprints.
     * A negative result represents value removed by contraction;
     * the refund rate is applied only when that value reaches the bank.
     */
    public static double townOutpostPrice(@NotNull CuboidDTO before, @NotNull CuboidDTO after) {
        return townOutpostPriceCents(before, after) / 100.0;
    }

    private static long townOutpostPriceCents(@NotNull CuboidDTO before, @NotNull CuboidDTO after) {
        if (!Configuration.town.outpostPricing.enable) {
            return 0;
        }
        return townOutpostValueCents(after.getSquare()) - townOutpostValueCents(before.getSquare());
    }

    private static long townOutpostValueCents(long area) {
        final long freeLimit = 500L * 500L;
        final long tier1000Limit = 1000L * 1000L;
        final long tier1500Limit = 1500L * 1500L;
        final long tier2000Limit = 2000L * 2000L;
        Configuration.Town.OutpostPricing pricing = Configuration.town.outpostPricing;

        long remaining = Math.max(0, area - freeLimit);
        double value = Math.min(remaining, tier1000Limit - freeLimit)
                * pricing.pricePerBlock500To1000;
        remaining = Math.max(0, remaining - (tier1000Limit - freeLimit));
        value += Math.min(remaining, tier1500Limit - tier1000Limit)
                * pricing.pricePerBlock1000To1500;
        remaining = Math.max(0, remaining - (tier1500Limit - tier1000Limit));
        value += Math.min(remaining, tier2000Limit - tier1500Limit)
                * pricing.pricePerBlock1500To2000;
        return Math.max(0L, Math.round(value * 100.0));
    }

    /**
     * Town-global amount check. Founding and contributed land count too, so
     * changing the command operator can never change a town's capacity.
     */
    public static int outpostLimit(@NotNull TownDOO town) {
        return outpostLimit(effectiveLevel(town), Configuration.town.outpostMaxCount);
    }

    public static int outpostLimitPerWorld(@NotNull TownDOO town) {
        return outpostLimit(effectiveLevel(town), Configuration.town.outpostMaxCountPerWorld);
    }

    static int outpostLimit(int level, int base) {
        if (base == -1) return -1;
        long limit = (long) base + (long) (effectiveLevel(level) - 1)
                * Configuration.town.outpostCountPerLevel;
        return limit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) limit;
    }

    public static int effectiveLevel(@NotNull TownDOO town) {
        return effectiveLevel(town.getLevel());
    }

    static int effectiveLevel(int level) {
        return Math.max(1, Math.min(level, Configuration.town.maxLevel));
    }

    public static void setTownLevel(@NotNull CommandSender sender, @NotNull TownDOO town,
                                    int level) throws Exception {
        if (!sender.isOp()) {
            throw new DominionException(Language.townManagerText.townLevelAdminOnly);
        }
        if (level < 1 || level > Configuration.town.maxLevel) {
            throw new DominionException(Language.townManagerText.townLevelInvalid,
                    Configuration.town.maxLevel);
        }
        TownLevelManager.setLevelByOperator(town.getId(), level);
        cache().reloadNow();
        TownDOO updated = cache().getTown(town.getId());
        TownDOO effectiveTown = updated == null ? town : updated;
        Notification.info(sender, Language.townManagerText.townLevelSet,
                effectiveTown.getName(), level, formatLimit(outpostLimit(effectiveTown)),
                formatLimit(outpostLimitPerWorld(effectiveTown)));
    }

    private static @NotNull String formatLimit(int limit) {
        return limit == -1 ? "∞" : String.valueOf(limit);
    }

    /** Apply the town-global full-height selection rule to a raw cuboid. */
    public static @NotNull CuboidDTO normalizeTownOutpostCuboid(@NotNull CuboidDTO cuboid) {
        if (!Configuration.town.outpostAutoIncludeVertical) return cuboid;
        return new CuboidDTO(cuboid.x1(), Configuration.town.outpostNoLowerThan, cuboid.z1(),
                cuboid.x2(), Configuration.town.outpostNoHigherThan - 1, cuboid.z2());
    }

    // ------------------------------------------------------------------
    // Return contributed outposts / admin arbitration
    // ------------------------------------------------------------------

    /**
     * Whether the sender may return this outpost to its contributor.
     * OPs retain arbitration access to every non-founding outpost. Ordinary
     * players must be the current mayor of the owning town, and may return
     * only contributed outposts (attached_by is the durable provenance flag).
     */
    public static boolean canReleaseOutpost(@NotNull CommandSender sender,
                                            @NotNull DominionDOO dominion) {
        Integer townId = dominion.getTownId();
        if (townId == null || "FOUNDING".equals(dominion.getClaimKind())) return false;
        if (sender.isOp()) return true;
        if (!(sender instanceof Player player) || dominion.getAttachedBy() == null) return false;
        TownDOO town = cache().getTown(townId);
        return town != null && town.getMayorUuid().equals(player.getUniqueId());
    }

    /**
     * Release an outpost back to its contributor. Authorization is enforced
     * here and re-checked under the town's database lock by the sync handler.
     */
    public static void releaseOutpost(@NotNull CommandSender operator,
                                      @NotNull DominionDOO dominion) throws Exception {
        Integer townId = dominion.getTownId();
        if (townId == null) {
            throw new DominionException(Language.townManagerText.releaseNotOutpost, dominion.getName());
        }
        if ("FOUNDING".equals(dominion.getClaimKind())) {
            throw new DominionException(Language.townManagerText.releaseFoundingDenied);
        }
        if (!canReleaseOutpost(operator, dominion)) {
            throw new DominionException(Language.townManagerText.releaseMayorContributedOnly);
        }
        TownDOO town = cache().getTown(townId);
        String townName = town == null ? "#" + townId : town.getName();
        UUID requiredMayor = operator.isOp() ? null : ((Player) operator).getUniqueId();
        TownGroupSyncHandler.releaseOutpost(dominion.getId(), requiredMayor);
        XLogger.info("Operator {0} released outpost {1} ({2}) from town {3} to contributor {4}.",
                operator.getName(), dominion.getName(), dominion.getId(), townName, dominion.getOwner());
        Notification.info(operator, Language.townManagerText.releaseSuccess,
                dominion.getName(), townName, CacheManager.instance.getPlayerName(dominion.getOwner()));
        if (!(operator instanceof Player player && player.getUniqueId().equals(dominion.getOwner()))) {
            notifyIfOnline(dominion.getOwner(), Language.townManagerText.releaseSuccess,
                    dominion.getName(), townName,
                    CacheManager.instance.getPlayerName(dominion.getOwner()));
        }
    }

    /**
     * Town-cascade privilege tiers (plan §5.1), evaluated after the
     * mayor/assistant/outpost-admin tier (handled by Asserts.checkDominionAdmin):
     * - town member (any role): the outpost's built-in member group flags decide;
     * - trusted player: trust only GRANTS — a mapped category set true returns
     *   true, anything else falls through to the legacy member/group/guest chain
     *   (never restricts below guest);
     * - returns null when the cascade has no opinion (stale town row, outsider).
     */
    public static @Nullable Boolean checkTownOutpostPrivilege(@NotNull DominionDOO dominion, @NotNull PriFlag flag,
                                                              @NotNull Player player) {
        Integer townId = dominion.getTownId();
        if (townId == null) return null;
        TownDOO town = cache().getTown(townId);
        if (town == null) return null;
        UUID uuid = player.getUniqueId();
        TownMemberDOO member = cache().getMember(townId, uuid);
        if (member != null) {
            if (member.getGroupId() > 0) {
                Boolean groupValue = townGroupFlagValue(dominion, member.getGroupId(), flag);
                if (groupValue != null) return groupValue;
            }
            // null (built-in @town group missing) falls through to the
            // legacy/guest chain rather than hard-denying every member
            return townMemberFlagValue(dominion, flag);
        }
        if (Configuration.town.trustEnabled) {
            TownTrustDOO trust = cache().getEffectiveTrust(townId, uuid, dominion.getId());
            if (trust != null) {
                TrustCategory category = trustCategoryOf(flag);
                if (category != null && trustGrants(trust, category)) {
                    return true;
                }
            }
        }
        return null;
    }

    private static @Nullable Boolean townGroupFlagValue(@NotNull DominionDOO dominion,
                                                         int townGroupId,
                                                         @NotNull PriFlag flag) {
        for (GroupDTO group : CacheManager.instance.getCache().getGroupCache()
                .getDominionGroups(dominion.getId())) {
            if (group.getTownGroupId() != null && group.getTownGroupId() == townGroupId) {
                return group.getFlagValue(flag);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Outpost admin (plan §2.4)
    // ------------------------------------------------------------------

    public static void setOutpostAdmin(@NotNull Player operator, @NotNull DominionDOO outpost,
                                       @Nullable UUID target) throws Exception {
        Integer townId = outpost.getTownId();
        TownDOO town = townId == null ? null : cache().getTown(townId);
        if (town == null) {
            throw new DominionException(Language.townManagerText.releaseNotOutpost, outpost.getName());
        }
        assertManager(operator, town);
        if (target == null) {
            outpost.setOutpostAdmin(null);
            Notification.info(operator, Language.townManagerText.outpostAdminCleared, outpost.getName());
            return;
        }
        if (cache().getMember(town.getId(), target) == null) {
            throw new DominionException(Language.townManagerText.outpostAdminNotMember, town.getName());
        }
        outpost.setOutpostAdmin(target);
        Notification.info(operator, Language.townManagerText.outpostAdminSet,
                outpost.getName(), CacheManager.instance.getPlayerName(target));
    }

    // ------------------------------------------------------------------
    // Attachment + member merging (plan §4.2)
    // ------------------------------------------------------------------

    /**
     * Attach a single dominion to a town (member merge + attach + member
     * group). Idempotent: already-attached dominions are skipped.
     */
    public static boolean attachOutpost(@NotNull TownDOO town, @NotNull DominionDOO dominion,
                                        @NotNull UUID operator) throws Exception {
        return attachOutpost(town, dominion, operator, dominion.getOwner());
    }

    /** Force path; the caller is responsible for administrator authorization. */
    public static boolean forceAttachOutpost(@NotNull TownDOO town, @NotNull DominionDOO dominion,
                                             @NotNull UUID operator) throws Exception {
        return attachOutpost(town, dominion, operator, null);
    }

    private static boolean attachOutpost(@NotNull TownDOO town, @NotNull DominionDOO dominion,
                                         @NotNull UUID operator,
                                         @Nullable UUID expectedOwner) throws Exception {
        if (dominion.isTownOutpost()) return false;
        List<Integer> attached = attachOwnedOutposts(
                town, List.of(dominion), operator, expectedOwner);
        return attached.contains(dominion.getId());
    }

    /**
     * Atomically attach a selected set of personal dominions. The owner is
     * rechecked while the rows are locked, so a stale GUI selection cannot
     * attach land that changed hands in the meantime.
     */
    public static @NotNull List<Integer> attachOwnedOutposts(
            @NotNull TownDOO town,
            @NotNull List<DominionDOO> candidates,
            @NotNull UUID operator,
            @NotNull UUID expectedOwner) throws Exception {
        return TownGroupSyncHandler.attachOutposts(
                town, candidates, operator, expectedOwner);
    }

    /**
     * Attach every dominion owned by the player to the town. Returns the
     * number of newly attached outposts. Idempotent: already-attached
     * dominions are skipped.
     */
    public static int attachPlayerDominions(@NotNull TownDOO town, @NotNull UUID player,
                                            @NotNull UUID operator) throws Exception {
        List<DominionDOO> candidates = ownedDominions(player).stream()
                .filter(dominion -> !dominion.isTownOutpost())
                .toList();
        return attachOwnedOutposts(town, candidates, operator, player).size();
    }

    /**
     * The town-member default flag value of an outpost, from its built-in
     * group. Null when the group is missing (fall through the cascade).
     */
    public static @Nullable Boolean townMemberFlagValue(@NotNull DominionDOO dominion, @NotNull PriFlag flag) {
        for (GroupDTO group : CacheManager.instance.getCache().getGroupCache().getDominionGroups(dominion.getId())) {
            if (group.getNamePlain().equals(TOWN_MEMBER_GROUP_NAME)) {
                return group.getFlagValue(flag);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Archiving (INSERT..SELECT keeps every flag column without
    // enumerating them; both archive tables mirror their source)
    // ------------------------------------------------------------------

    private static void archiveMembers(@NotNull DominionDOO dominion, @NotNull Integer townId,
                                       @NotNull String reason) throws SQLException {
        String columns = String.join(", ", memberColumns());
        String sql = "INSERT INTO dominion_member_archive (" + columns + ", town_id, archived_at, archive_reason) "
                + "SELECT " + columns + ", ?, CURRENT_TIMESTAMP, ? FROM dominion_member WHERE dom_id = ?";
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, townId);
            ps.setString(2, reason);
            ps.setInt(3, dominion.getId());
            ps.executeUpdate();
        }
    }

    private static void archiveDominion(@NotNull DominionDOO dominion, @NotNull Integer townId,
                                        @NotNull String reason) throws SQLException {
        String columns = String.join(", ", dominionColumns());
        String sql = "INSERT INTO dominion_archive (" + columns + ", archived_town_id, archived_at, archive_reason) "
                + "SELECT " + columns + ", ?, CURRENT_TIMESTAMP, ? FROM dominion WHERE id = ?";
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, townId);
            ps.setString(2, reason);
            ps.setInt(3, dominion.getId());
            ps.executeUpdate();
        }
    }

    private static List<String> memberColumns() throws SQLException {
        return new ArrayList<>(Show.show().columns().from("dominion_member").execute().keySet());
    }

    private static List<String> dominionColumns() throws SQLException {
        return new ArrayList<>(Show.show().columns().from("dominion").execute().keySet());
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /**
     * All dominions owned by the player (excluding the virtual root).
     */
    /**
     * Every dominion the player owns, across all backend servers. Deliberately
     * DB-backed (not the cache aggregate): attachment decisions must see
     * land created on another backend seconds ago, before its row reaches
     * the local cross-server replica.
     */
    public static @NotNull List<DominionDOO> ownedDominions(@NotNull UUID player) throws SQLException {
        List<DominionDOO> res = new ArrayList<>();
        for (DominionDOO doo : DominionDOO.selectByOwner(player)) {
            if (doo.getId() == -1) continue; // the virtual root
            res.add(doo);
        }
        return res;
    }

    public static @NotNull List<DominionDOO> outpostsOf(@NotNull Integer townId) throws SQLException {
        return DominionDOO.selectByTownId(townId);
    }

    private static void clearOutpostAdmin(@NotNull Integer townId, @NotNull UUID player) throws SQLException {
        for (DominionDOO outpost : outpostsOf(townId)) {
            if (player.equals(outpost.getOutpostAdminUuid())) {
                outpost.setOutpostAdmin(null);
            }
        }
    }

    private static @NotNull String uniqueDominionName(@NotNull String base) throws SQLException {
        String name = base;
        int i = 1;
        while (DominionDOO.select(name) != null) {
            name = base + "_" + i++;
        }
        return name;
    }

    private static void notifyIfOnline(@NotNull UUID player, @NotNull String message, Object... args) {
        Player online = cn.lunadeer.dominion.Dominion.instance.getServer().getPlayer(player);
        if (online != null) {
            Notification.info(online, message, args);
        }
    }
}
