package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.TownCache;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.Limitation;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.GroupDOO;
import cn.lunadeer.dominion.doos.MemberDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.syntax.Show.Show;
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
import java.util.Map;
import java.util.UUID;

import static cn.lunadeer.dominion.misc.Asserts.assertDominionIntersect;
import static cn.lunadeer.dominion.misc.Asserts.assertDominionName;
import static cn.lunadeer.dominion.misc.Asserts.assertDominionSize;
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

    public static class TownManagerText extends ConfigurationPart {
        public String townNameShouldNotEmpty = "Town name should not be empty.";
        public String townNameInvalid = "Town name should not contain space or dot.";
        public String townNameExist = "Town name {0} already exists.";
        public String townNotExist = "Town {0} does not exist.";
        public String alreadyInTown = "You are already a member of town {0}.";
        public String notInTown = "You are not a member of town {0}.";
        public String notTownManager = "Only the mayor or assistants of {0} can do this.";
        public String maxMembershipsReached = "You already belong to {0} town(s), the server limit is {1}.";
        public String joinWithLegacyLandDisabled = "Players owning legacy land cannot join towns on this server.";
        public String createSuccess = "Town {0} created. {1} outpost(s) attached.";
        public String createSuccessFounding = "Town {0} created with a founding outpost.";
        public String disbandSuccess = "Town {0} disbanded. {1} outpost(s) dissolved and archived.";
        public String disbandSuccessReturn = "Town {0} disbanded. {1} outpost(s) returned to contributors, {2} dissolved.";
        public String joinSuccess = "You joined town {0}. {1} outpost(s) attached to the town.";
        public String leaveSuccess = "You left town {0}. Your land stays with the town.";
        public String mayorCannotLeave = "The mayor cannot leave; transfer the mayor role or disband the town first.";
        public String kickSuccess = "Kicked {0} from town {1}.";
        public String cannotKickManager = "You cannot kick the mayor or an assistant.";
        public String onlyMayorCanTransfer = "Only the current mayor of {0} can transfer the mayor role.";
        public String transferSuccess = "Mayor of {0} transferred to {1}.";
        public String targetNotMember = "{0} is not a member of town {1}.";
        public String trustSuccess = "Trusted {0} on {1} (scope {2}).";
        public String untrustSuccess = "Removed trust of {0} on {1} (scope {2}).";
        public String trustDisabled = "The trust system is disabled on this server.";
        public String trustTargetOutpostNotInTown = "Outpost {0} does not belong to town {1}.";
        public String depositSuccess = "Deposited {0} into the bank of {1}. New balance: {2}.";
        public String withdrawSuccess = "Withdrew {0} from the bank of {1}. New balance: {2}.";
        public String insufficientTownFunds = "Town bank of {0} cannot afford {1} (balance: {2}).";
        public String invalidAmount = "Invalid amount: {0}.";
        public String outpostCreateSuccess = "Outpost {0} created for town {1}, cost {2} from the town bank.";
        public String outpostRemoteDisabled = "Remote outposts are disabled; towns may only resize existing outposts.";
        public String outpostAmountExceeded = "Town {0} has reached the outpost amount limit.";
        public String foundingTooLarge = "Founding outpost exceeds the max edge length of {0} blocks.";
        public String foundingRequired = "You own no land: a founding outpost location is required to create a town.";
        public String releaseSuccess = "Outpost {0} released from town {1}, returned to contributor {2}.";
        public String releaseNotOutpost = "Dominion {0} is not a town outpost.";
        public String releaseFoundingDenied = "A founding outpost can never be released; it dissolves with its town.";
        public String onlyMemberCanDeposit = "Only members of {0} can deposit into its bank.";
        public String outpostAdminSet = "Outpost admin of {0} set to {1}.";
        public String outpostAdminCleared = "Outpost admin of {0} cleared.";
        public String outpostAdminNotMember = "Outpost admin must be a member of town {0}.";
        public String mergeMemberMerged = "{0} merged into town {1} as resident.";
        public String mergeMemberTrusted = "{0} exceeded max-memberships; converted to outpost trust on {1}.";
    }

    /**
     * Built-in group carrying the town-member default flags of an outpost
     * (plan §5.1: reuse the existing group mechanism instead of a new flag
     * store). Mayors manage these flags through the normal group flag
     * commands; the reserved name keeps the lookup unambiguous.
     */
    public static final String TOWN_MEMBER_GROUP_NAME = "@town";

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

    public static void assertTownName(@NotNull String name) throws DominionException, SQLException {
        if (name.isEmpty()) {
            throw new DominionException(Language.townManagerText.townNameShouldNotEmpty);
        }
        if (name.contains(" ") || name.contains(".")) {
            throw new DominionException(Language.townManagerText.townNameInvalid);
        }
        if (cache().getTown(name) != null || TownDOO.select(name) != null) {
            throw new DominionException(Language.townManagerText.townNameExist, name);
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

    /**
     * Town-cascade management check for a town outpost: mayor/assistant or
     * the appointed outpost admin (plan §2.4). Used by Asserts to route
     * management commands (flags, tp point, resize, ...) of town outposts.
     */
    public static boolean isOutpostManager(@NotNull Player player, @NotNull DominionDOO dominion) {
        Integer townId = dominion.getTownId();
        if (townId == null) return false;
        TownDOO town = cache().getTown(townId);
        if (town == null) return false;
        if (isManager(town, player.getUniqueId())) return true;
        UUID outpostAdmin = dominion.getOutpostAdminUuid();
        return player.getUniqueId().equals(outpostAdmin);
    }

    // ------------------------------------------------------------------
    // Town lifecycle
    // ------------------------------------------------------------------

    /**
     * Create a town: the founder's every dominion attaches as outpost, or —
     * when the founder owns nothing — a founding outpost is created at the
     * given cuboid (required in that case). All validations that can fail
     * run BEFORE any row is inserted, so a failure never leaves an orphan
     * town behind.
     */
    public static @NotNull TownDOO createTown(@NotNull Player founder, @NotNull String name,
                                              @Nullable CuboidDTO foundingCuboid) throws Exception {
        assertTownName(name);
        assertCanJoinNewTown(founder);

        List<DominionDOO> owned = ownedDominions(founder.getUniqueId());

        // validate the founding outpost up front (town row does not exist yet)
        DominionDOO foundingToCreate = null;
        if (owned.isEmpty()) {
            if (foundingCuboid == null) {
                throw new DominionException(Language.townManagerText.foundingRequired);
            }
            int max = Configuration.town.foundingMaxSize;
            if (foundingCuboid.xLength() > max || foundingCuboid.zLength() > max) {
                throw new DominionException(Language.townManagerText.foundingTooLarge, max);
            }
            assertDominionSize(founder, founder.getWorld().getUID(), foundingCuboid);
            foundingToCreate = new DominionDOO(founder.getUniqueId(), uniqueDominionName(name),
                    founder.getWorld().getUID(), foundingCuboid, -1);
            assertDominionIntersect(founder, foundingToCreate, foundingCuboid);
            double cost = Configuration.town.foundingCost;
            if (cost > 0 && !bypassLimit(founder)
                    && VaultConnect.instance.getBalance(founder) < cost) {
                throw new DominionException(Language.townManagerText.insufficientTownFunds,
                        name, cost, VaultConnect.instance.getBalance(founder));
            }
        }

        TownDOO town = TownDOO.insert(name, founder.getUniqueId());
        TownMemberDOO.insert(town.getId(), founder.getUniqueId(),
                TownMemberDOO.TownRole.MAYOR, TownMemberDOO.MemberSource.FOUNDER);
        cache().load();

        if (foundingToCreate != null) {
            DominionDOO created = DominionDOO.insert(foundingToCreate);
            created.bornIntoTown(town.getId());
            created.setClaimKind("FOUNDING");
            ensureTownMemberGroup(created);
            double cost = Configuration.town.foundingCost;
            if (cost > 0 && !bypassLimit(founder)) {
                // the town bank starts empty, so the founding cost is
                // charged to the founder's wallet (free by default, §12.1)
                VaultConnect.instance.withdrawPlayer(founder, cost);
            }
            Notification.info(founder, Language.townManagerText.createSuccessFounding, name);
        } else {
            int attached = attachPlayerDominions(town, founder.getUniqueId(), founder.getUniqueId());
            Notification.info(founder, Language.townManagerText.createSuccess, name, attached);
        }
        cache().load();
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
        boolean dissolveAll = !"return-contributed".equals(Configuration.town.disbandPolicy);
        List<DominionDOO> outposts = outpostsOf(town.getId());
        int dissolved = 0;
        int returned = 0;
        for (DominionDOO outpost : outposts) {
            boolean contributed = outpost.getAttachedBy() != null;
            if (dissolveAll || !contributed) {
                archiveDominion(outpost, town.getId(), "DISBANDED_TOWN");
                archiveMembers(outpost, town.getId(), "DISBANDED_TOWN");
                DominionDOO.deleteById(outpost.getId());
                dissolved++;
            } else {
                outpost.detachFromTown();
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
        TownDOO.deleteById(town.getId());
        cn.lunadeer.dominion.commands.TownCommand.clearInvites(town.getName());
        cache().load();
        CacheManager.instance.getCache().getDominionCache().load();
        CacheManager.instance.getCache().getMemberCache().load();
        if (dissolveAll) {
            Notification.info(operator, Language.townManagerText.disbandSuccess, town.getName(), dissolved);
        } else {
            Notification.info(operator, Language.townManagerText.disbandSuccessReturn, town.getName(), returned, dissolved);
        }
    }

    /**
     * Join a town: every dominion the player owns attaches after
     * confirmation (handled by the command layer). The player becomes a
     * RESIDENT with source JOINED.
     */
    public static void joinTown(@NotNull Player player, @NotNull TownDOO town) throws Exception {
        if (cache().getMember(town.getId(), player.getUniqueId()) != null) {
            throw new DominionException(Language.townManagerText.alreadyInTown, town.getName());
        }
        if (!ownedDominions(player.getUniqueId()).isEmpty() && !Configuration.town.allowJoinWithLegacyLand) {
            throw new DominionException(Language.townManagerText.joinWithLegacyLandDisabled);
        }
        assertCanJoinNewTown(player);
        TownMemberDOO.insert(town.getId(), player.getUniqueId(),
                TownMemberDOO.TownRole.RESIDENT, TownMemberDOO.MemberSource.JOINED);
        int attached = attachPlayerDominions(town, player.getUniqueId(), player.getUniqueId());
        cache().load();
        Notification.info(player, Language.townManagerText.joinSuccess, town.getName(), attached);
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
        TownMemberDOO.delete(town.getId(), player.getUniqueId());
        clearOutpostAdmin(town.getId(), player.getUniqueId());
        cache().load();
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
        TownMemberDOO.delete(town.getId(), target);
        clearOutpostAdmin(town.getId(), target);
        cache().load();
        Notification.info(operator, Language.townManagerText.kickSuccess,
                CacheManager.instance.getPlayerName(target), town.getName());
        Player targetPlayer = operator.getServer().getPlayer(target);
        if (targetPlayer != null) {
            Notification.info(targetPlayer, Language.townManagerText.leaveSuccess, town.getName());
        }
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
        cache().load();
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
                && operator.getUniqueId().equals(outpost.getOutpostAdminUuid())) {
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
        cache().load();
        Notification.info(operator, Language.townManagerText.trustSuccess,
                CacheManager.instance.getPlayerName(target), town.getName(), scope.name());
    }

    public static void untrust(@NotNull Player operator, @NotNull TownDOO town, @NotNull UUID target,
                               @NotNull TownTrustDOO.TrustScope scope, @Nullable DominionDOO outpost) throws Exception {
        assertTrustOperator(operator, town, scope, outpost);
        int scopeId = scope == TownTrustDOO.TrustScope.TOWN ? town.getId()
                : (outpost == null ? -1 : outpost.getId());
        TownTrustDOO.delete(town.getId(), target, scope, scopeId);
        cache().load();
        Notification.info(operator, Language.townManagerText.untrustSuccess,
                CacheManager.instance.getPlayerName(target), town.getName(), scope.name());
    }

    // ------------------------------------------------------------------
    // Town bank
    // ------------------------------------------------------------------

    public static void deposit(@NotNull Player player, @NotNull TownDOO town, double amount) throws Exception {
        if (!(amount > 0) || !Double.isFinite(amount)) {
            throw new DominionException(Language.townManagerText.invalidAmount, amount);
        }
        if (cache().getMember(town.getId(), player.getUniqueId()) == null && !bypassLimit(player)) {
            throw new DominionException(Language.townManagerText.onlyMemberCanDeposit, town.getName());
        }
        VaultConnect.instance.withdrawPlayer(player, amount);
        town.adjustBalance(amount);
        cache().load();
        Notification.info(player, Language.townManagerText.depositSuccess,
                amount, town.getName(), town.getBalance());
    }

    public static void withdraw(@NotNull Player player, @NotNull TownDOO town, double amount) throws Exception {
        if (!(amount > 0) || !Double.isFinite(amount)) {
            throw new DominionException(Language.townManagerText.invalidAmount, amount);
        }
        assertManager(player, town);
        if (!town.canAfford(amount) && !bypassLimit(player)) {
            throw new DominionException(Language.townManagerText.insufficientTownFunds,
                    town.getName(), amount, town.getBalance());
        }
        town.adjustBalance(-amount);
        VaultConnect.instance.depositPlayer(player, amount);
        cache().load();
        Notification.info(player, Language.townManagerText.withdrawSuccess,
                amount, town.getName(), town.getBalance());
    }

    // ------------------------------------------------------------------
    // Town expansion (plan §4.6)
    // ------------------------------------------------------------------

    /**
     * Create a remote town outpost. Reuses the personal limitation pipeline
     * (size/Y, overlap, spawn protection) with the counting and charging
     * subjects switched to the town (plan §4.6/§12.1).
     */
    public static @NotNull DominionDOO createRemoteOutpost(@NotNull Player operator, @NotNull TownDOO town,
                                                           @NotNull String dominionName, @NotNull World world,
                                                           @NotNull CuboidDTO cuboid) throws Exception {
        assertManager(operator, town);
        if (!Configuration.town.outpostAllowRemote) {
            throw new DominionException(Language.townManagerText.outpostRemoteDisabled);
        }
        assertDominionName(dominionName);
        assertDominionSize(operator, world.getUID(), cuboid);
        assertTownOutpostAmount(operator, town, world.getUID());

        DominionDOO toCreate = new DominionDOO(operator.getUniqueId(), dominionName, world.getUID(), cuboid, -1);
        assertDominionIntersect(operator, toCreate, cuboid);

        double price = priceOf(operator, CuboidDTO.ZERO, cuboid);
        if (price > 0 && !bypassLimit(operator) && !town.canAfford(price)) {
            throw new DominionException(Language.townManagerText.insufficientTownFunds,
                    town.getName(), price, town.getBalance());
        }
        // insert first, charge after: a failed insert must never eat money
        DominionDOO created = DominionDOO.insert(toCreate);
        if (price > 0 && !bypassLimit(operator)) {
            town.adjustBalance(-price);
        }
        created.bornIntoTown(town.getId());
        ensureTownMemberGroup(created);
        cache().load();
        Notification.info(operator, Language.townManagerText.outpostCreateSuccess,
                dominionName, town.getName(), Math.max(price, 0));
        return created;
    }

    /**
     * Resize economy for town outposts: same price computation as
     * Asserts.assertEconomy, but the town bank is charged/refunded.
     */
    public static void chargeTownResize(@NotNull Player operator, @NotNull TownDOO town,
                                        @NotNull CuboidDTO before, @NotNull CuboidDTO after) throws Exception {
        double price = priceOf(operator, before, after);
        if (price > 0) {
            if (!town.canAfford(price)) {
                throw new DominionException(Language.townManagerText.insufficientTownFunds,
                        town.getName(), price, town.getBalance());
            }
            town.adjustBalance(-price);
        } else if (price < 0) {
            Limitation.Economy ecoConf = Configuration.getPlayerLimitation(operator).economy;
            town.adjustBalance(-price * ecoConf.refundRate);
        }
        cache().load();
    }

    private static double priceOf(@NotNull Player operator, @NotNull CuboidDTO before, @NotNull CuboidDTO after) {
        Limitation.Economy ecoConf = Configuration.getPlayerLimitation(operator).economy;
        if (!ecoConf.enable || bypassLimit(operator)) {
            return 0;
        }
        long amount = ecoConf.squareOnly ? after.minusSquareWith(before) : after.minusVolumeWith(before);
        return amount * ecoConf.pricePerBlock;
    }

    /**
     * Town-subject amount check: outposts of the town count against the
     * OPERATOR's limitation file (VIP ladder preserved, plan §4.6).
     */
    private static void assertTownOutpostAmount(@NotNull Player operator, @NotNull TownDOO town,
                                                @NotNull UUID worldUid) throws Exception {
        if (bypassLimit(operator)) return;
        Limitation limitation = Configuration.getPlayerLimitation(operator);
        if (limitation.amountAllOverTheWorld != -1
                && outpostsOf(town.getId()).size() >= limitation.amountAllOverTheWorld) {
            throw new DominionException(Language.townManagerText.outpostAmountExceeded, town.getName());
        }
        int worldAmount = limitation.getWorldSettings(worldUid).amount;
        if (worldAmount != -1 && countOutpostsInWorld(town.getId(), worldUid) >= worldAmount) {
            throw new DominionException(Language.townManagerText.outpostAmountExceeded, town.getName());
        }
    }

    // ------------------------------------------------------------------
    // Admin arbitration
    // ------------------------------------------------------------------

    /**
     * Release an outpost back to its contributor (plan §3: admin only).
     * Founding outposts can never be released (invariant 5).
     */
    public static void releaseOutpost(@NotNull CommandSender admin, @NotNull DominionDOO dominion) throws Exception {
        Integer townId = dominion.getTownId();
        if (townId == null) {
            throw new DominionException(Language.townManagerText.releaseNotOutpost, dominion.getName());
        }
        if ("FOUNDING".equals(dominion.getClaimKind())) {
            throw new DominionException(Language.townManagerText.releaseFoundingDenied);
        }
        TownDOO town = cache().getTown(townId);
        String townName = town == null ? "#" + townId : town.getName();
        dominion.detachFromTown();
        Notification.info(admin, Language.townManagerText.releaseSuccess,
                dominion.getName(), townName, CacheManager.instance.getPlayerName(dominion.getOwner()));
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
    public static void attachOutpost(@NotNull TownDOO town, @NotNull DominionDOO dominion,
                                     @NotNull UUID operator) throws Exception {
        if (dominion.isTownOutpost()) return;
        mergeMembers(town, dominion);
        dominion.attachToTown(town.getId(), operator);
        ensureTownMemberGroup(dominion);
    }

    /**
     * Attach every dominion owned by the player to the town. Returns the
     * number of newly attached outposts. Idempotent: already-attached
     * dominions are skipped.
     */
    public static int attachPlayerDominions(@NotNull TownDOO town, @NotNull UUID player,
                                            @NotNull UUID operator) throws Exception {
        int attached = 0;
        for (DominionDOO dominion : ownedDominions(player)) {
            if (dominion.isTownOutpost()) continue;
            attachOutpost(town, dominion, operator);
            attached++;
        }
        return attached;
    }

    /**
     * Merge the dominion's member overlay into the town (plan §4.2):
     * archive every member row, then per distinct player either merge as
     * RESIDENT (capacity allowing) or convert to an outpost-scoped trust.
     * The live dominion_member rows are then deleted.
     */
    private static void mergeMembers(@NotNull TownDOO town, @NotNull DominionDOO dominion) throws Exception {
        List<MemberDOO> members = MemberDOO.selectByDominionId(dominion.getId());
        if (members.isEmpty()) return;
        archiveMembers(dominion, town.getId(), "MERGED_INTO_TOWN");
        boolean mergeEnabled = Configuration.town.mergeMembersOnAttach;
        int max = Configuration.town.maxMemberships;
        for (MemberDOO member : members) {
            UUID uuid = member.getPlayerUUID();
            try {
                if (uuid.equals(dominion.getOwner())) {
                    // the contributor is covered by their own membership
                } else if (cache().getMember(town.getId(), uuid) != null) {
                    // already a member of this town
                } else if (mergeEnabled && (max == -1 || TownMemberDOO.countTownsOf(uuid) < max)) {
                    TownMemberDOO.insert(town.getId(), uuid,
                            TownMemberDOO.TownRole.RESIDENT, TownMemberDOO.MemberSource.MERGED);
                    notifyIfOnline(uuid, Language.townManagerText.mergeMemberMerged,
                            CacheManager.instance.getPlayerName(uuid), town.getName());
                } else {
                    boolean[] mapped = mapMemberFlagsToTrust(member);
                    TownTrustDOO.delete(town.getId(), uuid, TownTrustDOO.TrustScope.OUTPOST, dominion.getId());
                    TownTrustDOO.insert(town.getId(), uuid, TownTrustDOO.TrustScope.OUTPOST, dominion.getId(),
                            mapped[0], mapped[1], mapped[2], mapped[3], dominion.getOwner());
                    notifyIfOnline(uuid, Language.townManagerText.mergeMemberTrusted,
                            CacheManager.instance.getPlayerName(uuid), dominion.getName());
                }
            } catch (Exception e) {
                XLogger.error(e);
            }
            MemberDOO.deleteById(member.getId());
        }
    }

    /**
     * §4.5 lossy mapping: OR of the source PriFlags per trust boolean.
     * ADMIN/TELEPORT/FLY/PVP/... are deliberately dropped.
     */
    private static boolean[] mapMemberFlagsToTrust(@NotNull MemberDTO member) {
        boolean build = false, destroy = false, switchFlag = false, itemUse = false;
        for (Map.Entry<PriFlag, Boolean> entry : member.getFlagsValue().entrySet()) {
            if (!entry.getValue()) continue;
            TrustCategory category = trustCategoryOf(entry.getKey());
            if (category == null) continue;
            switch (category) {
                case BUILD -> build = true;
                case DESTROY -> destroy = true;
                case SWITCH -> switchFlag = true;
                case ITEM_USE -> itemUse = true;
            }
        }
        return new boolean[]{build, destroy, switchFlag, itemUse};
    }

    /**
     * Ensure the built-in town-member flag group exists on the outpost
     * (plan §5.1). Created with the standard group defaults (flags.yml).
     */
    public static void ensureTownMemberGroup(@NotNull DominionDOO dominion) throws SQLException {
        List<GroupDTO> groups = CacheManager.instance.getCache().getGroupCache().getDominionGroups(dominion.getId());
        for (GroupDTO group : groups) {
            if (group.getNamePlain().equals(TOWN_MEMBER_GROUP_NAME)) return;
        }
        GroupDOO.create(TOWN_MEMBER_GROUP_NAME, dominion);
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
    public static @NotNull List<DominionDOO> ownedDominions(@NotNull UUID player) {
        List<DominionDOO> res = new ArrayList<>();
        for (DominionDTO dto : CacheManager.instance.getPlayerOwnDominionDTOs(player)) {
            if (dto.getId() == -1) continue;
            if (dto instanceof DominionDOO doo) {
                res.add(doo);
            }
        }
        return res;
    }

    public static @NotNull List<DominionDOO> outpostsOf(@NotNull Integer townId) throws SQLException {
        return DominionDOO.selectByTownId(townId);
    }

    private static int countOutpostsInWorld(@NotNull Integer townId, @NotNull UUID worldUid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM dominion WHERE town_id = ? AND world_uid = ?";
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, townId);
            ps.setString(2, worldUid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
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
