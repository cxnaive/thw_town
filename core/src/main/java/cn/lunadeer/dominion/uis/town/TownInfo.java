package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.inputters.town.TownOutpostCreateInputter;
import cn.lunadeer.dominion.inputters.town.TownDisbandConfirmInputter;
import cn.lunadeer.dominion.inputters.town.TownRenameInputter;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.MainMenu;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestListView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.scui.configuration.ListViewConfiguration;
import cn.lunadeer.dominion.utils.stui.ListView;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.misc.Others.bypassLimit;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Town detail hub (plan §13.3). Every action button is rendered by role
 * (mayor/assistant/member/outsider) instead of failing after the click;
 * destructive actions keep the same confirmation flow as the commands.
 */
public class TownInfo extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownInfo ui = new TownInfo();
        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
            try {
                ui.prefetchedCount = DominionDOO.countByTownId(TownManager.requireTown(townName).getId());
            } catch (Exception e) {
                Notification.error(sender, e);
                return;
            }
            cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                    () -> ui.displayByPreference(sender, townName, pageStr));
        });
    }

    private Integer prefetchedCount;

    private int outpostCount(Integer townId) throws Exception {
        return prefetchedCount != null ? prefetchedCount : DominionDOO.countByTownId(townId);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townInfoTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    private static TownDOO requireTown(String townName) throws Exception {
        return TownManager.requireTown(townName);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownInfoTuiText extends ConfigurationPart {
        public String title = "Town: {0}";
        public String button = "TOWN";
        public String mayorLine = "§7Mayor: §b{0}";
        public String memberCountLine = "§7Members: §b{0}";
        public String balanceLine = "§7Bank balance: §b{0}";
        public String levelLine = "§7Town level: §b{0}§7/§b{1} §7- outpost limit: §b{2} total / {3} per world";
        public String levelTaskDescription = "View the shared town level task and submit items.";
        public String outpostCountLine = "§7Outposts: §b{0}";
        public String yourRoleLine = "§7Your role: §b{0}";
        public String permissionModeLegacy = "§7Permission mode: §6Legacy per-outpost";
        public String permissionModeTemplate = "§7Permission mode: §aTown templates";
        public String upgradeButton = "UPGRADE PERMISSIONS";
        public String upgradeDescription = "One-way reset: all outposts will inherit town templates.";
        public String upgradeWaiting = "§7Only the mayor may perform this irreversible upgrade.";
        public String upgradeConfirm = "§cClick again within 5 seconds to upgrade {0}. Every town-managed outpost group permission will be reset to inheritance. This cannot be undone.";
        public String membersButton = "MEMBERS";
        public String membersDescription = "View and manage town members.";
        public String trustsButton = "TRUSTS";
        public String trustsDescription = "View and manage trust grants.";
        public String bankButton = "BANK";
        public String bankDescription = "Town bank deposit/withdraw.";
        public String outpostsButton = "OUTPOSTS";
        public String outpostsDescription = "List outposts of this town.";
        public String createOutpostButton = "CREATE OUTPOST";
        public String createOutpostDescription = "Create from the two selected points and charge the town bank.";
        public String attachButton = "ATTACH";
        public String attachDescription = "Attach your personal dominions to this town.";
        public String groupsButton = "GROUPS";
        public String groupsDescription = "Manage custom groups shared by town outposts.";
        public String inviteButton = "INVITE";
        public String inviteDescription = "Invite a player to join.";
        public String leaveButton = "LEAVE";
        public String leaveDescription = "Leave this town (land stays).";
        public String renameButton = "RENAME TOWN";
        public String renameDescription = "Rename this town (mayor only).";
        public String disbandButton = "DISBAND";
        public String disbandDescription = "Disband this town (confirmation required).";
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = requireTown(args[0]);
            int page = toIntegrity(args[1], 1);
            TownMemberDOO.TownRole role = TownManager.roleOf(town, player.getUniqueId());
            boolean bypass = bypassLimit(player);
            boolean memberOrAdmin = role != null || bypass;
            boolean manager = TownManager.isManager(town, player.getUniqueId()) || bypass;
            boolean canDisband = role == TownMemberDOO.TownRole.MAYOR || bypass;
            boolean canRename = role == TownMemberDOO.TownRole.MAYOR || bypass;

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townInfoTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(MainMenu.button(player).build())
                    .append(TownList.button(player).build())
                    .append(TextUserInterface.townInfoTuiText.button));

            view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.mayorLine,
                    CacheManager.instance.getPlayerName(town.getMayorUuid()))));
            view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.memberCountLine,
                    CacheManager.instance.getTownCache().getMembersOf(town.getId()).size())));
            view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.balanceLine, town.getBalance())));
            view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.levelLine,
                    TownManager.effectiveLevel(town), Configuration.town.maxLevel,
                    formatLimit(TownManager.outpostLimit(town)),
                    formatLimit(TownManager.outpostLimitPerWorld(town)))));
            view.add(Line.create()
                    .append(TownLevelTasks.button(player, town.getName()).build())
                    .append(TextUserInterface.townInfoTuiText.levelTaskDescription));
            view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.outpostCountLine,
                    outpostCount(town.getId()))));
            if (role != null) {
                view.add(Line.create().append(formatString(TextUserInterface.townInfoTuiText.yourRoleLine,
                        TownManager.roleDisplay(role))));
            }
            if (town.isTemplatePermissionMode()) {
                view.add(Line.create().append(TextUserInterface.townInfoTuiText.permissionModeTemplate));
            } else {
                view.add(Line.create().append(TextUserInterface.townInfoTuiText.permissionModeLegacy));
                if (role == TownMemberDOO.TownRole.MAYOR || bypass) {
                    view.add(Line.create()
                            .append(new FunctionalButton(TextUserInterface.townInfoTuiText.upgradeButton) {
                                @Override
                                public void function() {
                                    confirmPermissionUpgrade(player, town);
                                }
                            }.needPermission(defaultPermission).build())
                            .append(TextUserInterface.townInfoTuiText.upgradeDescription));
                } else {
                    view.add(Line.create().append(TextUserInterface.townInfoTuiText.upgradeWaiting));
                }
            }

            view.add(Line.create()
                    .append(TownMembers.button(player, town.getName()).build())
                    .append(TextUserInterface.townInfoTuiText.membersDescription));
            view.add(Line.create()
                    .append(TownTrusts.button(player, town.getName()).build())
                    .append(TextUserInterface.townInfoTuiText.trustsDescription));
            view.add(Line.create()
                    .append(TownBank.button(player, town.getName()).build())
                    .append(TextUserInterface.townInfoTuiText.bankDescription));
            view.add(Line.create()
                    .append(TownOutposts.button(player, town.getName()).build())
                    .append(TextUserInterface.townInfoTuiText.outpostsDescription));

            if (memberOrAdmin) {
                view.add(Line.create()
                        .append(TownAttachSelector.button(player, town.getName()).build())
                        .append(TextUserInterface.townInfoTuiText.attachDescription));
                view.add(Line.create()
                        .append(TownGroups.button(player, town.getName()).build())
                        .append(TextUserInterface.townInfoTuiText.groupsDescription));
            }

            if (manager) {
                if (Configuration.town.outpostAllowRemote) {
                    view.add(Line.create().append(formatString(
                            Language.selectPointEventsHandlerText.selectionToolHint,
                            Configuration.selectTool)));
                    view.add(Line.create()
                            .append(TownOutpostCreateInputter.createTuiButtonOn(player, town.getName(),
                                    TextUserInterface.townInfoTuiText.createOutpostButton)
                                    .needPermission(defaultPermission).build())
                            .append(TextUserInterface.townInfoTuiText.createOutpostDescription));
                }
                view.add(Line.create()
                        .append(TownMembers.inviteButton(player, town.getName()).build())
                        .append(TextUserInterface.townInfoTuiText.inviteDescription));
            }
            if (canDisband) {
                view.add(Line.create()
                        .append(TownDisbandConfirmInputter.createTuiButtonOn(player, town.getName())
                                .needPermission(defaultPermission).build())
                        .append(TextUserInterface.townInfoTuiText.disbandDescription));
            }
            if (canRename) {
                view.add(Line.create()
                        .append(TownRenameInputter.createTuiButtonOn(player, town.getId())
                                .needPermission(defaultPermission).build())
                        .append(TextUserInterface.townInfoTuiText.renameDescription));
            }
            if (role != null && (role != TownMemberDOO.TownRole.MAYOR || bypass)) {
                view.add(Line.create()
                        .append(new FunctionalButton(TextUserInterface.townInfoTuiText.leaveButton) {
                            @Override
                            public void function() {
                                TownUI.action(player, () -> TownManager.leaveTown(player, town),
                                        () -> MainMenu.show(player, "1"));
                            }
                        }.needPermission(defaultPermission).build())
                        .append(TextUserInterface.townInfoTuiText.leaveDescription));
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownInfoCui extends ConfigurationPart {
        public String title = "§6✦ §e§lTown: {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<########",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "p#######n"
                )
        );
        public ButtonConfiguration membersButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§eMembers",
                List.of("§7View and manage town members.", "§a▶ Click to open"));
        public ButtonConfiguration trustsButton = ButtonConfiguration.createMaterial(
                'i', Material.NAME_TAG, "§eTrusts",
                List.of("§7View and manage trust grants.", "§a▶ Click to open"));
        public ButtonConfiguration bankButton = ButtonConfiguration.createMaterial(
                'i', Material.GOLD_INGOT, "§eBank",
                List.of("§7Balance: §b{0}", "§a▶ Click to open"));
        public ButtonConfiguration outpostsButton = ButtonConfiguration.createMaterial(
                'i', Material.FILLED_MAP, "§eOutposts",
                List.of("§7Total: §b{0}", "§a▶ Click to open"));
        public ButtonConfiguration levelButton = ButtonConfiguration.createMaterial(
                'i', Material.EXPERIENCE_BOTTLE, "§eTown Level",
                List.of("§7Level: §b{0}§7/§b{1}",
                        "§7Total outpost limit: §b{2}",
                        "§7Per-world outpost limit: §b{3}",
                        "§a▶ Click to view shared task"));
        public ButtonConfiguration createOutpostButton = ButtonConfiguration.createMaterial(
                'i', Material.GRASS_BLOCK, "§aCreate Outpost",
                List.of("§7Uses your two selected points.",
                        "§7The exact quote is shown after clicking.",
                        "§a▶ Click, then enter a name"));
        public ButtonConfiguration attachButton = ButtonConfiguration.createMaterial(
                'i', Material.LEAD, "§eAttach Dominions",
                List.of("§7Attach your personal dominions to this town.", "§a▶ Click to select"));
        public ButtonConfiguration groupsButton = ButtonConfiguration.createMaterial(
                'i', Material.CHEST, "§eTown Groups",
                List.of("§7Groups mirrored to every town outpost.", "§a▶ Click to manage"));
        public ButtonConfiguration inviteButton = ButtonConfiguration.createMaterial(
                'i', Material.WRITABLE_BOOK, "§aInvite",
                List.of("§7Invite a player to join.", "§a▶ Click to invite"));
        public ButtonConfiguration leaveButton = ButtonConfiguration.createMaterial(
                'i', Material.OAK_DOOR, "§cLeave",
                List.of("§7Leave this town (land stays).", "§c▶ Click to leave"));
        public ButtonConfiguration disbandButton = ButtonConfiguration.createMaterial(
                'i', Material.TNT, "§4Disband",
                List.of("§7Disband this town.", "§4▶ Click to confirm"));
        public ButtonConfiguration renameButton = ButtonConfiguration.createMaterial(
                'i', Material.NAME_TAG, "§eRename Town",
                List.of("§7Change this town's shared name.", "§7Mayor only.", "§e▶ Click to enter a name"));
        public ButtonConfiguration upgradeButton = ButtonConfiguration.createMaterial(
                'i', Material.ENCHANTED_BOOK, "§6Upgrade Permission Mode",
                List.of("§7Reset all town-managed outpost permissions.",
                        "§7All outposts will inherit town templates.",
                        "§cThis cannot be undone.", "", "§6▶ Click twice to upgrade"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = requireTown(args[0]);
            TownMemberDOO.TownRole role = TownManager.roleOf(town, player.getUniqueId());
            boolean bypass = bypassLimit(player);
            boolean memberOrAdmin = role != null || bypass;
            boolean manager = TownManager.isManager(town, player.getUniqueId()) || bypass;
            boolean canDisband = role == TownMemberDOO.TownRole.MAYOR || bypass;
            boolean canRename = role == TownMemberDOO.TownRole.MAYOR || bypass;

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townInfoCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townInfoCui.listConfiguration, 1);

            view = view.addItem(nav(player, ChestUserInterface.townInfoCui.membersButton,
                    () -> TownMembers.show(player, town.getName(), "1")));
            view = view.addItem(nav(player, ChestUserInterface.townInfoCui.trustsButton,
                    () -> TownTrusts.show(player, town.getName(), "1")));
            ChestButton bank = nav(player, ChestUserInterface.townInfoCui.bankButton,
                    () -> TownBank.show(player, town.getName(), "1"));
            bank = bank.setLoreArgs(String.valueOf(town.getBalance()));
            view = view.addItem(bank);
            ChestButton outposts = nav(player, ChestUserInterface.townInfoCui.outpostsButton,
                    () -> TownOutposts.show(player, town.getName(), "1"));
            outposts = outposts.setLoreArgs(String.valueOf(outpostCount(town.getId())));
            view = view.addItem(outposts);
            ChestButton level = nav(player, ChestUserInterface.townInfoCui.levelButton,
                    () -> TownLevelTasks.show(player, town.getName(), "1"));
            view = view.addItem(level.setLoreArgs(String.valueOf(TownManager.effectiveLevel(town)),
                    String.valueOf(Configuration.town.maxLevel),
                    formatLimit(TownManager.outpostLimit(town)),
                    formatLimit(TownManager.outpostLimitPerWorld(town))));

            if (memberOrAdmin) {
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.attachButton,
                        () -> TownAttachSelector.show(player, town.getName(), "1")));
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.groupsButton,
                        () -> TownGroups.show(player, town.getName(), "1")));
            }
            if (manager) {
                if (Configuration.town.outpostAllowRemote) {
                    ChestButton createOutpost = nav(player, ChestUserInterface.townInfoCui.createOutpostButton,
                            () -> TownOutpostCreateInputter.createOn(player, town.getName()), true);
                    view = view.addItem(createOutpost);
                }
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.inviteButton,
                        () -> TownMembers.createInviteOn(player, town.getName()), true));
            }
            if (canDisband) {
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.disbandButton,
                        () -> TownDisbandConfirmInputter.createOn(player, town.getName()), true));
            }
            if (canRename) {
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.renameButton,
                        () -> TownRenameInputter.createOn(player, town.getId()), true));
            }
            if (!town.isTemplatePermissionMode()
                    && (role == TownMemberDOO.TownRole.MAYOR || bypass)) {
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.upgradeButton,
                        () -> confirmPermissionUpgrade(player, town)));
            }
            if (role != null && (role != TownMemberDOO.TownRole.MAYOR || bypass)) {
                view = view.addItem(nav(player, ChestUserInterface.townInfoCui.leaveButton,
                        () -> TownUI.action(player, () -> TownManager.leaveTown(player, town),
                                () -> MainMenu.show(player, "1"))));
            }

            view.setButton(ChestUserInterface.townInfoCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townInfoCui.backButton) {
                        @Override
                        public void onClick(ClickType type) {
                            TownList.show(player, "1");
                        }
                    });
            view.open();
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    private static ChestButton nav(Player player, ButtonConfiguration config, Runnable action) {
        return nav(player, config, action, false);
    }

    private static void confirmPermissionUpgrade(Player player, TownDOO town) {
        cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                player, "town-permission-upgrade:" + town.getId(),
                () -> TownUI.action(player,
                        () -> TownManager.upgradePermissionMode(player, town),
                        () -> TownGroups.show(player, town.getName(), "1")),
                formatString(TextUserInterface.townInfoTuiText.upgradeConfirm, town.getName()));
    }

    private static ChestButton nav(Player player, ButtonConfiguration config, Runnable action,
                                   boolean closeBefore) {
        return new ChestButton(config) {
            @Override
            public void onClick(ClickType type) {
                try {
                    if (closeBefore) {
                        ChestUserInterfaceManager.getInstance().getViewOf(player).close();
                    }
                } catch (Exception ignored) {
                }
                action.run();
            }
        };
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = requireTown(args[0]);
        Notification.info(sender, formatString(TextUserInterface.townInfoTuiText.mayorLine,
                CacheManager.instance.getPlayerName(town.getMayorUuid())));
        Notification.info(sender, formatString(TextUserInterface.townInfoTuiText.memberCountLine,
                CacheManager.instance.getTownCache().getMembersOf(town.getId()).size()));
        Notification.info(sender, formatString(TextUserInterface.townInfoTuiText.balanceLine, town.getBalance()));
        Notification.info(sender, formatString(TextUserInterface.townInfoTuiText.levelLine,
                TownManager.effectiveLevel(town), Configuration.town.maxLevel,
                formatLimit(TownManager.outpostLimit(town)),
                formatLimit(TownManager.outpostLimitPerWorld(town))));
        Notification.info(sender, formatString(TextUserInterface.townInfoTuiText.outpostCountLine,
                outpostCount(town.getId())));
        List<String> members = new ArrayList<>();
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            members.add(CacheManager.instance.getPlayerName(member.getPlayerUuid()) + "(" + member.getRole() + ")");
        }
        Notification.info(sender, String.join(", ", members));
    }

    private static @NotNull String formatLimit(int limit) {
        return limit == -1 ? "∞" : String.valueOf(limit);
    }
}
