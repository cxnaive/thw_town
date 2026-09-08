package cn.lunadeer.dominion.commands;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.PlayerDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.TownLevelManager;
import cn.lunadeer.dominion.managers.TownInviteManager;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static cn.lunadeer.dominion.misc.Converts.getSelectedPoints;
import static cn.lunadeer.dominion.misc.Converts.toPlayer;

/**
 * /town command group. Implemented as a standalone TabExecutor (not
 * CommandManager/SecondaryCommand) because CommandManager's command
 * registry is static and shared across root commands — town subcommand
 * names (create, leave, ...) would collide with dominion's.
 * <p>
 * join requires a pending invite from the town's mayor/assistant (plan
 * §7.1 threat model); invitations are shared through the database and
 * expire after 5 minutes, so they can be accepted on any backend.
 */
public class TownCommand implements TabExecutor {

    public static class TownCommandText extends ConfigurationPart {
        public String onlyPlayer = "This command can only be executed by a player.";
        public String usage = "&b/town create <name> &7- create a town\n"
                + "&b/town invite <player> &7- invite a player (mayor/assistant)\n"
                + "&b/town join <town> [confirm] &7- join an invited town\n"
                + "&b/town rename <new name> &7- rename your town (mayor only)\n"
                + "&b/town leave &7- leave your town (land stays with the town)\n"
                + "&b/town attach [dominion|all] &7- attach dominions to your town (GUI if no args)\n"
                + "&b/town group <create|rename|delete|list|info|add|remove> ... &7- manage town groups\n"
                + "&b/town kick <player> &7- kick a resident (mayor/assistant)\n"
                + "&b/town transfer <player> &7- transfer the mayor role\n"
                + "&b/town promote|demote <player> &7- set assistant/resident role (mayor only)\n"
                + "&b/town disband [confirm] &7- disband the town (dissolves outposts)\n"
                + "&b/town deposit <amount> &7- deposit into the town bank\n"
                + "&b/town withdraw <amount> &7- withdraw from the town bank (mayor/assistant)\n"
                + "&b/town outpost create <name> &7- create a remote outpost (selected points)\n"
                + "&b/town admin <outpost> [player] &7- set/clear the outpost admin\n"
                + "&b/town upgrade [confirm] &7- one-way upgrade to town permission templates (mayor only)\n"
                + "&b/town release <outpost> [confirm] &7- mayor returns contributed land (OP arbitration)\n"
                + "&b/town level [submit] &7- view the shared level task or submit inventory items\n"
                + "&b/town level set <town> <level> &7- set town level (OP only)\n"
                + "&b/town trust <player> <town|outpost <name>> <build> <destroy> <switch> <item_use>\n"
                + "&b/town untrust <player> [town|outpost <name>]\n"
                + "&b/town info [town] &7- town information\n"
                + "&b/town list &7- list towns";
        public String unknownSubCommand = "Unknown town command: {0}";
        public String noInvite = "You need an invite from the mayor or an assistant of {0} to join.";
        public String noInviteRemote = "You need an invite from the mayor or an assistant of {0} to join.";
        public String inviteSent = "Invited {0} to join {1} (valid for 5 minutes).";
        public String inviteReceived = "You have been invited to join {0} (valid for 5 minutes). Run /town join {0} confirm on any server to accept.";
        public String inviteNotLocal = "Invitation saved for {0}; they will be notified when online.";
        public String joinConfirm = "Joining {0} makes you a resident; your {1} personal dominion(s) stay personal: {2}. Run /town join {0} confirm to proceed. Use /town attach afterward if you want to contribute selected land.";
        public String joinConfirmNoLand = "Joining {0} makes you a resident; your personal dominions are not attached automatically. Run /town join {0} confirm to proceed.";
        public String createConfirm = "Creating town {0} makes you its mayor; your {1} personal dominion(s) stay personal: {2}. Run /town create {0} confirm to proceed. Use /town attach afterward if you want to contribute selected land.";
        public String attachMemberNote = "Member lists of attached dominions are archived; affected members receive a per-outpost trust grant mapped from their flags and are notified.";
        public String disbandConfirm = "Disbanding {0} dissolves its {1} outpost(s): {2}. Re-run with 'confirm' to proceed.";
        public String disbandConfirmContributors = "Contributed land of these players will be dissolved: {0}.";
        public String disbandConfirmReturn = "Disbanding {0} returns contributed land and dissolves town-native outposts ({1} total): {2}. Re-run with 'confirm' to proceed.";
        public String disbandConfirmContributorsReturn = "Contributed land of these players will be returned to them: {0}.";
        public String dominionNotExist = "Dominion {0} does not exist.";
        public String noTown = "You are not in any town.";
        public String noManagedTown = "You are not the mayor or an assistant of any town.";
        public String townInfo = "&b{0} &7(mayor: {1}, members: {2}, outposts: {3}, bank: {4})";
        public String townLevelInfo = "&7Town level: &b{0}&7/{1}, outpost limit: &b{2} total / {3} per world";
        public String levelUsage = "&b/town level set <town> <1-{0}> &7- set town level (OP only)";
        public String levelTaskUsage = "&b/town level [submit] &7- view the shared task or submit matching inventory items";
        public String townListEntry = "{0} (mayor: {1})";
        public String needPoints = "Select two points with the select tool first.";
        public String playerNotFound = "Player {0} not found.";
        public String memberList = "Members of {0}: {1}";
        public String attachAllSuccess = "Attached {0} dominion(s) to town {1}.";
        public String attachOneSuccess = "Attached dominion {0} to town {1}.";
        public String attachNotOwned = "You don't own a personal dominion named {0}.";
        public String upgradeConfirm = "Upgrading {0} resets every town-managed outpost group permission and makes all outposts inherit new town templates. This cannot be undone. Run /town upgrade confirm to proceed.";
        public String releaseConfirm = "Releasing {0} returns it to contributor {1}, removes outpost trust and all town group mirrors. Run /town release {0} confirm to proceed.";
        public String townGroupUsage = "&b/town group create <name> [display name]\n"
                + "&b/town group rename <name> <display name>\n"
                + "&b/town group delete <name>\n"
                + "&b/town group list\n"
                + "&b/town group info <name>\n"
                + "&b/town group add <player> <group>\n"
                + "&b/town group remove <player>";
        public String townGroupListHeader = "Town groups of {0}:";
        public String townGroupListEntry = "&6{0} &7({1}, members: {2})";
        public String townGroupListEmpty = "Town {0} has no custom groups.";
        public String townGroupInfo = "&6{0} &7(internal name: {1}, members: {2})";
        public String townGroupMembers = "Members: {0}";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            // bare /town opens the town UI (own town, or the town list
            // when townless); console gets the usage text
            if (sender instanceof Player player) {
                cn.lunadeer.dominion.doos.TownDOO own = TownManager.ownTownOf(player);
                if (own != null) {
                    cn.lunadeer.dominion.uis.town.TownInfo.show(player, own.getName(), "1");
                } else {
                    cn.lunadeer.dominion.uis.town.TownList.show(player, "1");
                }
            } else {
                Notification.info(sender, Language.townCommandText.usage);
            }
            return true;
        }
        // Town operations run real database I/O (merge/archive/disband can
        // touch many rows); keep the main thread free, following the
        // provider handlers' completeAsync precedent.
        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> dispatch(sender, args));
        return true;
    }

    private void dispatch(@NotNull CommandSender sender, @NotNull String[] args) {
        try {
            switch (args[0].toLowerCase()) {
                case "create" -> handleCreate(sender, args);
                case "rename" -> handleRename(sender, args);
                case "disband" -> handleDisband(sender, args);
                case "invite" -> handleInvite(sender, args);
                case "join" -> handleJoin(sender, args);
                case "leave" -> handleLeave(sender);
                case "attach" -> handleAttach(sender, args);
                case "group" -> handleGroup(sender, args);
                case "kick" -> handleKick(sender, args);
                case "transfer" -> handleTransfer(sender, args);
                case "promote" -> handleSetRole(sender, args, cn.lunadeer.dominion.doos.TownMemberDOO.TownRole.ASSISTANT);
                case "demote" -> handleSetRole(sender, args, cn.lunadeer.dominion.doos.TownMemberDOO.TownRole.RESIDENT);
                case "trust" -> handleTrust(sender, args, true);
                case "untrust" -> handleTrust(sender, args, false);
                case "deposit" -> handleBank(sender, args, true);
                case "withdraw" -> handleBank(sender, args, false);
                case "outpost" -> handleOutpost(sender, args);
                case "admin" -> handleAdmin(sender, args);
                case "upgrade" -> handleUpgrade(sender, args);
                case "release" -> handleRelease(sender, args);
                case "level" -> handleLevel(sender, args);
                case "info" -> handleInfo(sender, args);
                case "list" -> handleList(sender);
                default -> {
                    Notification.error(sender, Language.townCommandText.unknownSubCommand, args[0]);
                    Notification.info(sender, Language.townCommandText.usage);
                }
            }
        } catch (Exception e) {
            Notification.error(sender, e);
        }
    }

    // ------------------------------------------------------------------

    private void handleCreate(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        cn.lunadeer.dominion.misc.Asserts.assertCreationAllowed(player, player.getWorld());
        String name = requireArg(args, 1);
        // Show the founder which personal dominions are available for a
        // later manual attachment; creating the town does not attach them.
        List<DominionDOO> attachable = new ArrayList<>();
        for (DominionDOO dominion : TownManager.ownedDominions(player.getUniqueId())) {
            if (!dominion.isTownOutpost()) {
                attachable.add(dominion);
            }
        }
        if (attachable.isEmpty()) {
            // no legacy land — the founding-outpost flow handles it
            createTownInteractive(player, name);
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Notification.warn(sender, Language.townCommandText.createConfirm,
                    name, attachable.size(), outpostNames(attachable));
            Notification.warn(sender, Language.townCommandText.attachMemberNote);
            return;
        }
        TownManager.createTown(player, name, null);
    }

    private void handleRename(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        TownDOO town;
        String newName;
        if (args.length == 2) {
            town = resolveOwnTown(sender);
            newName = requireArg(args, 1);
        } else if (args.length == 3 && sender.isOp()) {
            // OP arbitration for a town they do not belong to:
            // /town rename <current-name> <new-name>
            town = TownManager.requireTown(requireArg(args, 1));
            newName = requireArg(args, 2);
        } else {
            Notification.info(sender, Language.townCommandText.usage);
            return;
        }
        TownManager.renameTown(sender, town, newName);
    }

    /**
     * Shared by the command and the main-menu create-town inputter:
     * players with no land must provide a founding cuboid (selection, or
     * the auto square around their location).
     */
    public static void createTownInteractive(@NotNull Player player, @NotNull String name) throws Exception {
        cn.lunadeer.dominion.misc.Asserts.assertCreationAllowed(player, player.getWorld());
        List<DominionDOO> attachable = new ArrayList<>();
        for (DominionDOO dominion : TownManager.ownedDominions(player.getUniqueId())) {
            if (!dominion.isTownOutpost()) {
                attachable.add(dominion);
            }
        }
        if (!attachable.isEmpty()) {
            // the menu inputter path has no 'confirm' argument — gate town
            // creation behind a second inputter exactly like /town disband
            Notification.warn(player, Language.townCommandText.createConfirm,
                    name, attachable.size(), outpostNames(attachable));
            Notification.warn(player, Language.townCommandText.attachMemberNote);
            new cn.lunadeer.dominion.utils.stui.inputter.InputterRunner(
                    player, Language.townCreateInputterText.confirmHint) {
                @Override
                public void run(String input) {
                    if (!input.trim().equalsIgnoreCase("confirm")) {
                        Notification.warn(player, Language.townCreateInputterText.confirmHint);
                        return;
                    }
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
                        try {
                            TownManager.createTown(player, name, null);
                        } catch (Exception e) {
                            Notification.error(player, e);
                        }
                    });
                }
            };
            return;
        }
        TownManager.createTown(player, name, foundingCuboidAt(player));
    }

    private void handleDisband(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        TownDOO town = resolveManagedTown(sender);
        TownManager.assertCanDisband(sender, town);
        List<DominionDOO> outposts = TownManager.outpostsOf(town.getId());
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            // §7.1: disband destroys other players' contributed land too —
            // the confirmation must name the contributors
            List<String> contributors = new ArrayList<>();
            for (DominionDOO outpost : outposts) {
                if (outpost.getAttachedBy() == null) continue; // town-native
                String contributor = CacheManager.instance.getPlayerName(outpost.getOwner());
                if (!contributors.contains(contributor)) {
                    contributors.add(contributor);
                }
            }
            boolean dissolveAll = !"return-contributed".equals(Configuration.town.disbandPolicy);
            if (dissolveAll) {
                Notification.warn(sender, Language.townCommandText.disbandConfirm,
                        town.getName(), outposts.size(), outpostNames(outposts));
                if (!contributors.isEmpty()) {
                    Notification.warn(sender, Language.townCommandText.disbandConfirmContributors,
                            String.join(", ", contributors));
                }
            } else {
                // the message announces the dissolution of town-NATIVE
                // outposts only; contributed land is returned instead
                List<DominionDOO> townNative = new ArrayList<>();
                for (DominionDOO outpost : outposts) {
                    if (outpost.getAttachedBy() == null) {
                        townNative.add(outpost);
                    }
                }
                Notification.warn(sender, Language.townCommandText.disbandConfirmReturn,
                        town.getName(), townNative.size(), outpostNames(townNative));
                if (!contributors.isEmpty()) {
                    Notification.warn(sender, Language.townCommandText.disbandConfirmContributorsReturn,
                            String.join(", ", contributors));
                }
            }
            return;
        }
        TownManager.disbandTown(sender, town);
    }

    private void handleInvite(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveManagedTown(sender);
        TownManager.assertManager(player, town);
        PlayerDTO target = requirePlayerDto(args, 1);
        TownInviteManager.PendingInvite invite = TownInviteManager.sendInvite(
                town.getId(), target.getUuid(), player.getUniqueId(), town.getName());
        Notification.info(sender, Language.townCommandText.inviteSent, target.getLastKnownName(), town.getName());
        TownInviteManager.notifyIfOnline(invite);
    }

    private void handleJoin(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town;
        String townName = requireArg(args, 1);
        try {
            town = TownManager.requireTown(townName);
        } catch (cn.lunadeer.dominion.misc.DominionException missingFromCache) {
            // Join commands run asynchronously and may safely query the
            // shared database while another backend's cache is still stale.
            town = TownManager.requireTownFromSharedDatabase(townName);
        }
        // validate everything that does not depend on consent BEFORE touching
        // the invite, so a failed attempt never burns it
        if (CacheManager.instance.getTownCache().getMember(town.getId(), player.getUniqueId()) != null) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townManagerText.alreadyInTown, town.getName());
        }
        List<DominionDOO> attachable = new ArrayList<>();
        for (DominionDOO dominion : TownManager.ownedDominions(player.getUniqueId())) {
            if (!dominion.isTownOutpost()) {
                attachable.add(dominion);
            }
        }
        // every validation that does not need consent must run before the
        // invite is consumed, so a failed join never burns it
        TownManager.assertCanJoinNewTown(player);
        boolean bypass = Others.bypassLimit(player);
        if (!bypass && !TownInviteManager.hasInvite(town.getId(), player.getUniqueId())) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                        Language.townCommandText.noInvite,
                        town.getName());
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            if (attachable.isEmpty()) {
                Notification.warn(sender, Language.townCommandText.joinConfirmNoLand, town.getName());
            } else {
                Notification.warn(sender, Language.townCommandText.joinConfirm,
                        town.getName(), attachable.size(), outpostNames(attachable));
                Notification.warn(sender, Language.townCommandText.attachMemberNote);
            }
            return;
        }
        TownManager.joinTown(player, town, !bypass);
    }

    private void handleLeave(@NotNull CommandSender sender) throws Exception {
        Player player = requirePlayer(sender);
        TownManager.leaveTown(player, resolveOwnTown(sender));
    }

    private void handleAttach(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveOwnTown(sender);
        
        if (args.length == 1) {
            // Open GUI selector
            cn.lunadeer.dominion.uis.town.TownAttachSelector.show(player, town.getName(), "1");
        } else if (args[1].equalsIgnoreCase("all")) {
            // Attach all personal dominions (database query for cross-server)
            int count = TownManager.attachPlayerDominions(town, player.getUniqueId(), player.getUniqueId());
            CacheManager.instance.reloadCache();
            Notification.info(player, Language.townCommandText.attachAllSuccess, count, town.getName());
        } else {
            // Attach specific dominion by name (database query for cross-server)
            String dominionName = args[1];
            DominionDOO dominion = DominionDOO.selectByOwner(player.getUniqueId()).stream()
                    .filter(d -> !d.isTownOutpost() && d.getName().equalsIgnoreCase(dominionName))
                    .findFirst()
                    .orElseThrow(() -> new cn.lunadeer.dominion.misc.DominionException(
                            Language.townCommandText.attachNotOwned, dominionName));
            if (!TownManager.attachOutpost(town, dominion, player.getUniqueId())) {
                throw new cn.lunadeer.dominion.misc.DominionException(
                        Language.townManagerText.outpostAttachUnavailable, dominion.getName());
            }
            CacheManager.instance.reloadCache();
            Notification.info(player, Language.townCommandText.attachOneSuccess,
                    dominion.getName(), town.getName());
        }
    }

    private void handleGroup(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveOwnTown(sender);
        if (args.length == 1) {
            cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                    () -> cn.lunadeer.dominion.uis.town.TownGroups.show(player, town.getName(), "1"));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> {
                String displayName = args.length <= 3 ? null
                        : String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                TownManager.createTownGroup(player, town, requireArg(args, 2), displayName);
            }
            case "rename" -> {
                TownGroupDOO group = TownManager.requireTownGroup(town, requireArg(args, 2));
                requireArg(args, 3);
                String displayName = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                TownManager.updateTownGroupDisplayName(player, town, group, displayName);
            }
            case "delete" -> TownManager.deleteTownGroup(player, town,
                    TownManager.requireTownGroup(town, requireArg(args, 2)));
            case "list" -> {
                List<TownGroupDOO> groups = TownManager.groupsOf(town);
                if (groups.isEmpty()) {
                    Notification.info(sender, Language.townCommandText.townGroupListEmpty, town.getName());
                    return;
                }
                Notification.info(sender, Language.townCommandText.townGroupListHeader, town.getName());
                for (TownGroupDOO group : groups) {
                    Notification.info(sender, Language.townCommandText.townGroupListEntry,
                            group.getDisplayName(), group.getName(), groupMemberCount(town, group));
                }
            }
            case "info" -> {
                TownGroupDOO group = TownManager.requireTownGroup(town, requireArg(args, 2));
                List<String> members = groupMemberNames(town, group);
                Notification.info(sender, Language.townCommandText.townGroupInfo,
                        group.getDisplayName(), group.getName(), members.size());
                Notification.info(sender, Language.townCommandText.townGroupMembers,
                        members.isEmpty() ? "-" : String.join(", ", members));
            }
            case "add" -> {
                PlayerDTO target = requirePlayerDto(args, 2);
                TownGroupDOO group = TownManager.requireTownGroup(town, requireArg(args, 3));
                TownManager.assignTownGroup(player, town, target.getUuid(), group);
            }
            case "remove" -> TownManager.removeTownGroupAssignment(player, town,
                    requirePlayerDto(args, 2).getUuid());
            default -> Notification.info(sender, Language.townCommandText.townGroupUsage);
        }
    }

    private static int groupMemberCount(@NotNull TownDOO town, @NotNull TownGroupDOO group) {
        int count = 0;
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            if (member.getGroupId() == group.getId()) count++;
        }
        return count;
    }

    private static @NotNull List<String> groupMemberNames(@NotNull TownDOO town,
                                                           @NotNull TownGroupDOO group) {
        List<String> names = new ArrayList<>();
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            if (member.getGroupId() == group.getId()) {
                names.add(CacheManager.instance.getPlayerName(member.getPlayerUuid()));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }


    private void handleKick(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveManagedTown(sender);
        PlayerDTO target = requirePlayerDto(args, 1);
        TownManager.kickMember(player, town, target.getUuid());
    }

    private void handleTransfer(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveManagedTown(sender);
        PlayerDTO target = requirePlayerDto(args, 1);
        TownManager.transferMayor(player, town, target.getUuid());
    }

    private void handleSetRole(@NotNull CommandSender sender, @NotNull String[] args,
                               @NotNull cn.lunadeer.dominion.doos.TownMemberDOO.TownRole role) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveManagedTown(sender);
        TownManager.setRole(player, town, requirePlayerDto(args, 1).getUuid(), role);
    }

    private void handleTrust(@NotNull CommandSender sender, @NotNull String[] args, boolean grant) throws Exception {
        Player player = requirePlayer(sender);
        PlayerDTO target = requirePlayerDto(args, 1);
        // resolve scope first: OUTPOST-scoped trust may be managed by the
        // outpost admin (who is not necessarily a town manager), so the town
        // is resolved from the outpost; TOWN scope needs a managed town.
        cn.lunadeer.dominion.doos.TownTrustDOO.TrustScope scope = cn.lunadeer.dominion.doos.TownTrustDOO.TrustScope.TOWN;
        DominionDOO outpost = null;
        if (args.length >= 3 && args[2].equalsIgnoreCase("outpost")) {
            scope = cn.lunadeer.dominion.doos.TownTrustDOO.TrustScope.OUTPOST;
            outpost = requireOutpost(args, 3);
        } else if (args.length >= 3 && !args[2].equalsIgnoreCase("town")) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.unknownSubCommand, args[2]);
        }
        TownDOO town = outpost != null && outpost.getTownId() != null
                ? TownManager.requireTown(outpost.getTownId())
                : resolveManagedTown(sender);
        if (!grant) {
            TownManager.untrust(player, town, target.getUuid(), scope, outpost);
            return;
        }
        // /town trust <player> town <b> <d> <s> <i>
        // /town trust <player> outpost <name> <b> <d> <s> <i>
        int boolOffset = scope == cn.lunadeer.dominion.doos.TownTrustDOO.TrustScope.TOWN ? 3 : 4;
        boolean build = parseBool(args, boolOffset);
        boolean destroy = parseBool(args, boolOffset + 1);
        boolean switchFlag = parseBool(args, boolOffset + 2);
        boolean itemUse = parseBool(args, boolOffset + 3);
        TownManager.trust(player, town, target.getUuid(), scope, outpost, build, destroy, switchFlag, itemUse);
    }

    private void handleBank(@NotNull CommandSender sender, @NotNull String[] args, boolean deposit) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveOwnTown(sender);
        double amount = Double.parseDouble(requireArg(args, 1));
        if (deposit) {
            TownManager.deposit(player, town, amount);
        } else {
            TownManager.withdraw(player, town, amount);
        }
    }

    private void handleOutpost(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveManagedTown(sender);
        if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
            String name = requireArg(args, 2);
            Location[] points = getSelectedPoints(player);
            if (points == null || points[0] == null || points[1] == null) {
                throw new cn.lunadeer.dominion.misc.DominionException(
                        Language.selectPointEventsHandlerText.selectionToolHint,
                        Configuration.selectTool);
            }
            org.bukkit.World selectedWorld = points[0].getWorld();
            if (selectedWorld == null) {
                throw new cn.lunadeer.dominion.misc.DominionException(
                        Language.selectPointEventsHandlerText.selectionToolHint,
                        Configuration.selectTool);
            }
            CuboidDTO cuboid = TownManager.normalizeTownOutpostCuboid(
                    new CuboidDTO(points[0], points[1]));
            double price = Others.bypassLimit(player) ? 0
                    : TownManager.townOutpostPrice(CuboidDTO.ZERO, cuboid);
            Notification.info(player, Language.selectPointEventsHandlerText.townOutpostPrice,
                    price, VaultConnect.instance.currencyNamePluralOrEmpty());
            TownManager.createRemoteOutpost(player, town, name, selectedWorld, cuboid);
        } else {
            Notification.info(sender, Language.townCommandText.usage);
        }
    }

    private void handleAdmin(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        DominionDOO outpost = requireOutpost(args, 1);
        UUID target = args.length >= 3 ? requirePlayerDto(args, 2).getUuid() : null;
        TownManager.setOutpostAdmin(player, outpost, target);
    }

    private void handleUpgrade(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        Player player = requirePlayer(sender);
        TownDOO town = resolveOwnTown(sender);
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            Notification.warn(sender, Language.townCommandText.upgradeConfirm, town.getName());
            return;
        }
        TownManager.upgradePermissionMode(player, town);
    }

    private void handleRelease(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        DominionDOO outpost = requireOutpost(args, 1);
        if (!outpost.isTownOutpost()) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    Language.townManagerText.releaseNotOutpost, outpost.getName());
        }
        if ("FOUNDING".equals(outpost.getClaimKind())) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    Language.townManagerText.releaseFoundingDenied);
        }
        if (!TownManager.canReleaseOutpost(sender, outpost)) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    Language.townManagerText.releaseMayorContributedOnly);
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            Notification.warn(sender, Language.townCommandText.releaseConfirm,
                    outpost.getName(), CacheManager.instance.getPlayerName(outpost.getOwner()));
            return;
        }
        TownManager.releaseOutpost(sender, outpost);
    }

    private void handleInfo(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        TownDOO town = args.length >= 2
                ? TownManager.requireTown(args[1])
                : resolveOwnTown(sender);
        int members = CacheManager.instance.getTownCache().getMembersOf(town.getId()).size();
        int outposts = TownManager.outpostsOf(town.getId()).size();
        Notification.info(sender, Language.townCommandText.townInfo,
                town.getName(), CacheManager.instance.getPlayerName(town.getMayorUuid()),
                // Keep the two trailing arguments for installations that
                // briefly used the combined 5.1.0 preview template.  The
                // restored five-argument template ignores them, while the
                // preview template still formats cleanly instead of showing
                // literal {5}/{6} placeholders.
                members, outposts, town.getBalance(), TownManager.effectiveLevel(town),
                formatLimit(TownManager.outpostLimit(town)),
                formatLimit(TownManager.outpostLimitPerWorld(town)));
        // Preview builds placed level/capacity directly in town-info using
        // {5}/{6}.  Keep such customized/preview files working without a
        // duplicate line; older five-argument files receive the new line.
        if (!Language.townCommandText.townInfo.contains("{5}")
                && !Language.townCommandText.townInfo.contains("{6}")) {
            Notification.info(sender, Language.townCommandText.townLevelInfo,
                    TownManager.effectiveLevel(town), Configuration.town.maxLevel,
                    formatLimit(TownManager.outpostLimit(town)),
                    formatLimit(TownManager.outpostLimitPerWorld(town)));
        }
    }

    private void handleLevel(@NotNull CommandSender sender, @NotNull String[] args) throws Exception {
        if (args.length == 1) {
            if (sender instanceof Player player) {
                TownDOO town = resolveOwnTown(sender);
                cn.lunadeer.dominion.uis.town.TownLevelTasks.show(
                        player, town.getName(), "1");
            } else {
                Notification.info(sender, Language.townCommandText.levelTaskUsage);
                Notification.info(sender, Language.townCommandText.levelUsage,
                        Configuration.town.maxLevel);
            }
            return;
        }
        if (args[1].equalsIgnoreCase("submit")) {
            Player player = requirePlayer(sender);
            TownDOO town = resolveOwnTown(sender);
            TownLevelManager.contributeFromInventory(player, town,
                    () -> cn.lunadeer.dominion.uis.town.TownLevelTasks.show(
                            player, town.getName(), "1"));
            return;
        }
        if (!sender.isOp()) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    Language.townManagerText.townLevelAdminOnly);
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("set")) {
            Notification.info(sender, Language.townCommandText.levelUsage, Configuration.town.maxLevel);
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException ignored) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    Language.townManagerText.townLevelInvalid, Configuration.town.maxLevel);
        }
        TownManager.setTownLevel(sender, TownManager.requireTown(args[2]), level);
    }

    private static @NotNull String formatLimit(int limit) {
        return limit == -1 ? "∞" : String.valueOf(limit);
    }

    private void handleList(@NotNull CommandSender sender) throws Exception {
        List<String> entries = new ArrayList<>();
        for (TownDOO town : CacheManager.instance.getTownCache().getAllTowns()) {
            entries.add(Language.townCommandText.townListEntry
                    .replace("{0}", town.getName())
                    .replace("{1}", CacheManager.instance.getPlayerName(town.getMayorUuid())));
        }
        Notification.info(sender, String.join(", ", entries));
    }

    // ------------------------------------------------------------------

    private static @NotNull Player requirePlayer(@NotNull CommandSender sender) throws Exception {
        return toPlayer(sender);
    }

    private static @NotNull String requireArg(@NotNull String[] args, int index) throws Exception {
        if (args.length <= index || args[index].isEmpty()) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.usage);
        }
        return args[index];
    }

    private static @NotNull PlayerDTO requirePlayerDto(@NotNull String[] args, int index) throws Exception {
        String name = requireArg(args, index);
        PlayerDTO dto = CacheManager.instance.getPlayer(name);
        if (dto == null) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.playerNotFound, name);
        }
        return dto;
    }

    private static @NotNull DominionDOO requireOutpost(@NotNull String[] args, int index) throws Exception {
        String name = requireArg(args, index);
        DominionDTO dto = CacheManager.instance.getDominion(name);
        if (!(dto instanceof DominionDOO doo)) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.dominionNotExist, name);
        }
        return doo;
    }

    /**
     * The town the sender belongs to. With multiple memberships (config
     * allowing), prefer the town they actually lead — mayor over
     * assistant over resident — instead of raw database row order, so
     * bank/land operations hit the intended town.
     */
    private static @NotNull TownDOO resolveOwnTown(@NotNull CommandSender sender) throws Exception {
        if (!(sender instanceof Player player)) {
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.onlyPlayer);
        }
        List<TownMemberDOO> memberships = new ArrayList<>(CacheManager.instance.getTownCache()
                .getMembershipsOf(player.getUniqueId()));
        memberships.sort(Comparator.comparingInt(m -> roleRank(m.getRole())));
        for (TownMemberDOO membership : memberships) {
            TownDOO town = CacheManager.instance.getTownCache().getTown(membership.getTownId());
            if (town != null) return town;
        }
        throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.noTown);
    }

    /**
     * The town the sender manages (membership with the highest manager
     * role). Admins who actually hold a manager role resolve normally;
     * admins acting on arbitrary towns use the admin commands instead.
     */
    private static @NotNull TownDOO resolveManagedTown(@NotNull CommandSender sender) throws Exception {
        if (sender instanceof Player player) {
            List<TownMemberDOO> memberships = new ArrayList<>(CacheManager.instance.getTownCache()
                    .getMembershipsOf(player.getUniqueId()));
            memberships.sort(Comparator.comparingInt(m -> roleRank(m.getRole())));
            for (TownMemberDOO membership : memberships) {
                if (membership.getRole() == TownMemberDOO.TownRole.RESIDENT) continue;
                TownDOO town = CacheManager.instance.getTownCache().getTown(membership.getTownId());
                if (town != null) return town;
            }
            throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.noManagedTown);
        }
        throw new cn.lunadeer.dominion.misc.DominionException(Language.townCommandText.onlyPlayer);
    }

    private static int roleRank(@NotNull TownMemberDOO.TownRole role) {
        return switch (role) {
            case MAYOR -> 0;
            case ASSISTANT -> 1;
            case RESIDENT -> 2;
        };
    }

    private static @NotNull String outpostNames(@NotNull List<DominionDOO> outposts) {
        List<String> names = new ArrayList<>();
        for (DominionDOO outpost : outposts) {
            names.add(outpost.getName());
        }
        return String.join(", ", names);
    }


    private static boolean parseBool(@NotNull String[] args, int index) throws Exception {
        String v = requireArg(args, index).toLowerCase();
        return v.equals("true") || v.equals("yes") || v.equals("1");
    }

    /**
     * Founding cuboid around the player. This starter cube deliberately uses
     * founding-size instead of either legacy limitation or remote-outpost
     * selection rules.
     */
    private static @NotNull CuboidDTO foundingCuboidAt(@NotNull Player player) {
        // a literal foundingSize^3 cube centered on the player (e.g.
        // 32x32x32 by default) — deliberately NOT the world's full
        // vertical span; expansion is what town resize is for
        int size = Configuration.town.foundingSize;
        int half = size / 2;
        Location loc = player.getLocation();
        int x1 = loc.getBlockX() - half;
        int y1 = loc.getBlockY() - half;
        int z1 = loc.getBlockZ() - half;
        return new CuboidDTO(x1, y1, z1, x1 + size - 1, y1 + size - 1, z1 + size - 1);
    }

    // ------------------------------------------------------------------
    private static final List<String> SUB_COMMANDS = List.of("create", "rename", "disband", "invite", "join", "leave",
            "attach", "kick", "transfer", "promote", "demote", "trust", "untrust", "deposit", "withdraw", "outpost",
            "admin", "upgrade", "release", "level", "group", "info", "list");
    private static final List<String> GROUP_SUB_COMMANDS = List.of(
            "create", "rename", "delete", "list", "info", "add", "remove");

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            List<String> visible = new ArrayList<>(SUB_COMMANDS);
            if (!canUseReleaseCommand(sender)) {
                visible.remove("release");
            }
            if (!(sender instanceof Player) && !sender.isOp()) {
                visible.remove("level");
            }
            filter(visible, args[0], suggestions);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "join", "info" -> filter(townNames(), args[1], suggestions);
                case "rename" -> {
                    // For OP arbitration the first argument is the current
                    // town name; ordinary mayors type the new name freely.
                    if (sender.isOp()) filter(townNames(), args[1], suggestions);
                }
                case "attach" -> {
                    if (sender instanceof Player player) {
                        List<String> options = new ArrayList<>(List.of("all"));
                        try {
                            options.addAll(DominionDOO.selectByOwner(player.getUniqueId()).stream()
                                    .filter(d -> !d.isTownOutpost())
                                    .map(DominionDOO::getName)
                                    .toList());
                        } catch (Exception ignored) {
                        }
                        filter(options, args[1], suggestions);
                    }
                }
                case "invite", "kick", "transfer", "trust", "untrust", "promote", "demote" ->
                        filter(playerNames(), args[1], suggestions);
                case "admin" -> filter(dominionNames(), args[1], suggestions);
                case "release" -> filter(releasableOutpostNames(sender), args[1], suggestions);
                case "upgrade" -> filter(List.of("confirm"), args[1], suggestions);
                case "level" -> {
                    List<String> options = new ArrayList<>();
                    if (sender instanceof Player) options.add("submit");
                    if (sender.isOp()) options.add("set");
                    filter(options, args[1], suggestions);
                }
                case "group" -> filter(GROUP_SUB_COMMANDS, args[1], suggestions);
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "create", "join", "disband" -> filter(List.of("confirm"), args[2], suggestions);
                case "release" -> {
                    DominionDTO dominion = CacheManager.instance.getDominion(args[1]);
                    if (dominion instanceof DominionDOO outpost
                            && TownManager.canReleaseOutpost(sender, outpost)) {
                        filter(List.of("confirm"), args[2], suggestions);
                    }
                }
                case "trust", "untrust" -> filter(List.of("town", "outpost"), args[2], suggestions);
                case "admin" -> filter(playerNames(), args[2], suggestions);
                case "level" -> {
                    if (sender.isOp() && args[1].equalsIgnoreCase("set")) {
                        filter(townNames(), args[2], suggestions);
                    }
                }
                case "group" -> {
                    if (args[1].equalsIgnoreCase("rename") || args[1].equalsIgnoreCase("delete")
                            || args[1].equalsIgnoreCase("info")) {
                        filter(townGroupNames(sender), args[2], suggestions);
                    } else if (args[1].equalsIgnoreCase("add")) {
                        filter(townMemberNames(sender, false), args[2], suggestions);
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        filter(townMemberNames(sender, true), args[2], suggestions);
                    }
                }
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("level") && sender.isOp()
                    && args[1].equalsIgnoreCase("set")) {
                List<String> levels = new ArrayList<>();
                for (int i = 1; i <= Configuration.town.maxLevel; i++) {
                    levels.add(String.valueOf(i));
                }
                filter(levels, args[3], suggestions);
            } else if (args[0].equalsIgnoreCase("group") && args[1].equalsIgnoreCase("add")) {
                filter(townGroupNames(sender), args[3], suggestions);
            } else if (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust")) {
                if (args[2].equalsIgnoreCase("outpost")) {
                    filter(dominionNames(), args[3], suggestions);
                } else if (args[0].equalsIgnoreCase("trust")) {
                    filter(List.of("true", "false"), args[3], suggestions);
                }
            }
        } else if (args.length >= 5 && args[0].equalsIgnoreCase("trust")) {
            filter(List.of("true", "false"), args[args.length - 1], suggestions);
        }
        return suggestions;
    }

    private static void filter(@NotNull Iterable<String> options, @NotNull String prefix, @NotNull List<String> out) {
        String lower = prefix.toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                out.add(option);
            }
        }
    }

    private static @NotNull List<String> townNames() {
        List<String> names = new ArrayList<>();
        for (TownDOO town : CacheManager.instance.getTownCache().getAllTowns()) {
            names.add(town.getName());
        }
        return names;
    }

    private static @NotNull List<String> playerNames() {
        return CacheManager.instance.getPlayerNames();
    }

    private static @NotNull List<String> dominionNames() {
        return CacheManager.instance.getAllDominionNames();
    }

    private static boolean canUseReleaseCommand(@NotNull CommandSender sender) {
        if (sender.isOp()) return true;
        if (!(sender instanceof Player player)) return false;
        for (TownMemberDOO membership : CacheManager.instance.getTownCache()
                .getMembershipsOf(player.getUniqueId())) {
            TownDOO town = CacheManager.instance.getTownCache().getTown(membership.getTownId());
            if (town != null && town.getMayorUuid().equals(player.getUniqueId())) return true;
        }
        return false;
    }

    private static @NotNull List<String> releasableOutpostNames(@NotNull CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (String name : CacheManager.instance.getAllDominionNames()) {
            DominionDTO dominion = CacheManager.instance.getDominion(name);
            if (dominion instanceof DominionDOO outpost
                    && TownManager.canReleaseOutpost(sender, outpost)) {
                names.add(name);
            }
        }
        return names;
    }

    private static @NotNull List<String> townGroupNames(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) return List.of();
        TownDOO town = TownManager.ownTownOf(player);
        if (town == null) return List.of();
        return TownManager.groupsOf(town).stream().map(TownGroupDOO::getName).toList();
    }

    private static @NotNull List<String> townMemberNames(@NotNull CommandSender sender,
                                                          boolean assignedOnly) {
        if (!(sender instanceof Player player)) return List.of();
        TownDOO town = TownManager.ownTownOf(player);
        if (town == null) return List.of();
        List<String> names = new ArrayList<>();
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            if (!assignedOnly || member.getGroupId() > 0) {
                names.add(CacheManager.instance.getPlayerName(member.getPlayerUuid()));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
