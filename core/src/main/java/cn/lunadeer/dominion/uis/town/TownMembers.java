package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.TownInviteManager;
import cn.lunadeer.dominion.uis.AbstractUI;
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
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Town member management (plan §13.3): list, invite (inputter), kick,
 * promote/demote, transfer mayor. Action buttons render only for
 * mayor/assistant (transfer: mayor only), matching the command gates.
 */
public class TownMembers extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        new TownMembers().displayByPreference(sender, townName, pageStr);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townMembersTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    // ------------------------------------------------------------------
    // invite (shared by TUI button and CUI button)
    // ------------------------------------------------------------------

    public static void createInviteOn(CommandSender sender, String townName) {
        new InputterRunner(sender, TextUserInterface.townMembersTuiText.inviteHint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> runAsync(sender, input));
            }

            private void runAsync(CommandSender sender, String input) {
                try {
                    Player player = (Player) sender;
                    TownDOO town = TownManager.requireTown(townName);
                    TownManager.assertManager(player, town);
                    cn.lunadeer.dominion.api.dtos.PlayerDTO target =
                            CacheManager.instance.getPlayer(input.trim());
                    if (target == null) {
                        Notification.error(sender, Language.townCommandText.playerNotFound, input);
                        return;
                    }
                    TownInviteManager.PendingInvite invite = TownInviteManager.sendInvite(
                            town.getId(), target.getUuid(), player.getUniqueId(), town.getName());
                    Notification.info(sender, Language.townCommandText.inviteSent,
                            target.getLastKnownName(), town.getName());
                    TownInviteManager.notifyIfOnline(invite);
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runEntityTask(
                            () -> show(player, townName, "1"), player);
                } catch (Exception e) {
                    Notification.error(sender, e);
                }
            }
        };
    }

    public static FunctionalButton inviteButton(CommandSender sender, String townName) {
        return new FunctionalButton(TextUserInterface.townMembersTuiText.inviteButton) {
            @Override
            public void function() {
                createInviteOn(sender, townName);
            }
        };
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownMembersTuiText extends ConfigurationPart {
        public String title = "Members of {0}";
        public String button = "MEMBERS";
        public String memberLine = "§b{0} §7- Role: §b{1} §7| Source: §b{2}";
        public String inviteButton = "INVITE";
        public String inviteHint = "Enter the player name to invite.";
        public String kickButton = "KICK";
        public String promoteButton = "PROMOTE";
        public String demoteButton = "DEMOTE";
        public String transferButton = "TRANSFER";
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);
            boolean manager = TownManager.isManager(town, player.getUniqueId())
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            boolean isMayor = TownManager.roleOf(town, player.getUniqueId()) == TownMemberDOO.TownRole.MAYOR
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townMembersTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townMembersTuiText.button));

            if (manager) {
                view.add(Line.create().append(inviteButton(player, town.getName())
                        .needPermission(defaultPermission).build()));
            }
            for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
                String name = CacheManager.instance.getPlayerName(member.getPlayerUuid());
                Line line = Line.create()
                        .append(formatString(TextUserInterface.townMembersTuiText.memberLine,
                                name, TownManager.roleDisplay(member.getRole()),
                                TownManager.sourceDisplay(member.getSource())));
                if (manager && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.kickButton,
                            () -> cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "kick:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> {
                                        try {
                                            TownManager.kickMember(player, town, member.getPlayerUuid());
                                        } catch (Exception e) {
                                            Notification.error(player, e);
                                        }
                                    },
                                    formatString(Language.townManagerText.confirmKick, name)
                            )).build());
                }
                if (isMayor && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.promoteButton,
                            () -> cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "promote:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> {
                                        try {
                                            TownManager.setRole(player, town, member.getPlayerUuid(),
                                                    TownMemberDOO.TownRole.ASSISTANT);
                                        } catch (Exception e) {
                                            Notification.error(player, e);
                                        }
                                    },
                                    formatString(Language.townManagerText.confirmPromote, name)
                            )).build());
                }
                if (isMayor && member.getRole() == TownMemberDOO.TownRole.ASSISTANT) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.demoteButton,
                            () -> cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "demote:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> {
                                        try {
                                            TownManager.setRole(player, town, member.getPlayerUuid(),
                                                    TownMemberDOO.TownRole.RESIDENT);
                                        } catch (Exception e) {
                                            Notification.error(player, e);
                                        }
                                    },
                                    formatString(Language.townManagerText.confirmDemote, name)
                            )).build());
                }
                // the mayor may transfer to ANY member, not just assistants
                if (isMayor && member.getRole() != TownMemberDOO.TownRole.MAYOR) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.transferButton,
                            () -> cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "transfer:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> {
                                        try {
                                            TownManager.transferMayor(player, town, member.getPlayerUuid());
                                        } catch (Exception e) {
                                            Notification.error(player, e);
                                        }
                                    },
                                    formatString(Language.townManagerText.confirmTransfer, name)
                            )).build());
                }
                view.add(line);
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    private cn.lunadeer.dominion.utils.stui.components.buttons.PermissionButton actionButton(
            Player player, TownDOO town, TownMemberDOO member, String label, TownAction townAction) {
        return new FunctionalButton(label) {
            @Override
            public void function() {
                TownUI.action(player, townAction::run, () -> show(player, town.getName(), "1"));
            }
        }.needPermission(defaultPermission);
    }

    private interface TownAction {
        void run() throws Exception;
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownMembersCui extends ConfigurationPart {
        public String title = "§6✦ §eMembers of {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<########",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "p######An"
                )
        );
        public ButtonConfiguration residentItemButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§e{0}",
                List.of(
                        "§7Role: §b{0}",
                        "§7Source: §b{1}",
                        "",
                        "§a▶ Left Click: promote (mayor only)",
                        "§c▶ Right Click: kick (managers)"
                )
        );
        public ButtonConfiguration assistantItemButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§e{0}",
                List.of(
                        "§7Role: §b{0}",
                        "§7Source: §b{1}",
                        "",
                        "§c▶ Left Click: demote (mayor only)"
                )
        );
        public ButtonConfiguration mayorItemButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§6👑 §e{0}",
                List.of(
                        "§7Role: §b{0}",
                        "§7Source: §b{1}",
                        "",
                        "§7This is the town's mayor.",
                        "§8No actions available."
                )
        );
        public ButtonConfiguration inviteItemButton = ButtonConfiguration.createMaterial(
                'A', Material.WRITABLE_BOOK, "§aInvite player",
                List.of("§7Invite a player to join.", "§a▶ Click to invite"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            boolean manager = TownManager.isManager(town, player.getUniqueId())
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            boolean isMayor = TownManager.roleOf(town, player.getUniqueId()) == TownMemberDOO.TownRole.MAYOR
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townMembersCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townMembersCui.listConfiguration, toIntegrity(args[1], 1));

            for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
                String name = CacheManager.instance.getPlayerName(member.getPlayerUuid());
                // Select button configuration by member role
                ButtonConfiguration buttonConfig = switch (member.getRole()) {
                    case RESIDENT -> ChestUserInterface.townMembersCui.residentItemButton;
                    case ASSISTANT -> ChestUserInterface.townMembersCui.assistantItemButton;
                    case MAYOR -> ChestUserInterface.townMembersCui.mayorItemButton;
                };
                ChestButton btn = new ChestButton(buttonConfig) {
                    @Override
                    public void onClick(ClickType type) {
                        // Left click: promote/demote (mayor only)
                        // Right click: kick resident (manager)
                        // Mayor row: inert
                        if (type.isLeftClick() && isMayor && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                            cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "promote:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> TownUI.action(player, () -> TownManager.setRole(player, town,
                                            member.getPlayerUuid(), TownMemberDOO.TownRole.ASSISTANT),
                                            () -> show(player, town.getName(), "1")),
                                    formatString(Language.townManagerText.confirmPromote, name)
                            );
                        } else if (type.isLeftClick() && isMayor
                                && member.getRole() == TownMemberDOO.TownRole.ASSISTANT) {
                            cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "demote:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> TownUI.action(player, () -> TownManager.setRole(player, town,
                                            member.getPlayerUuid(), TownMemberDOO.TownRole.RESIDENT),
                                            () -> show(player, town.getName(), "1")),
                                    formatString(Language.townManagerText.confirmDemote, name)
                            );
                        } else if (type.isRightClick() && manager
                                && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                            cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                                    player,
                                    "kick:" + town.getId() + ":" + member.getPlayerUuid(),
                                    () -> TownUI.action(player, () -> TownManager.kickMember(player, town,
                                            member.getPlayerUuid()), () -> show(player, town.getName(), "1")),
                                    formatString(Language.townManagerText.confirmKick, name)
                            );
                        }
                    }
                };
                btn = btn.setDisplayNameArgs(name);
                btn = btn.setLoreArgs(TownManager.roleDisplay(member.getRole()),
                        TownManager.sourceDisplay(member.getSource()));
                view = view.addItem(btn);
            }
            final ChestListView viewFinal = view;
            if (manager) {
                view.setButton(ChestUserInterface.townMembersCui.inviteItemButton.getSymbol(),
                        new ChestButton(ChestUserInterface.townMembersCui.inviteItemButton) {
                            @Override
                            public void onClick(ClickType type) {
                                viewFinal.close();
                                createInviteOn(player, town.getName());
                            }
                        });
            }
            view.setButton(ChestUserInterface.townMembersCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townMembersCui.backButton) {
                        @Override
                        public void onClick(ClickType type) {
                            TownInfo.show(player, town.getName(), "1");
                        }
                    });
            view.open();
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            Notification.info(sender, formatString(TextUserInterface.townMembersTuiText.memberLine,
                    CacheManager.instance.getPlayerName(member.getPlayerUuid()),
                    TownManager.roleDisplay(member.getRole()),
                    TownManager.sourceDisplay(member.getSource())));
        }
    }
}
