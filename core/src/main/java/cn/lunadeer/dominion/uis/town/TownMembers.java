package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.commands.TownCommand;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
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
                    TownCommand.sendInvite(town.getName(), target.getUuid());
                    Notification.info(sender, Language.townCommandText.inviteSent,
                            target.getLastKnownName(), town.getName());
                    Player online = cn.lunadeer.dominion.Dominion.instance.getServer().getPlayer(target.getUuid());
                    if (online != null) {
                        Notification.info(online, Language.townCommandText.inviteSent,
                                target.getLastKnownName(), town.getName());
                    }
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                            () -> show(sender, townName, "1"));
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
        public String memberLine = "§b{0} §7- {1} ({2})";
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
                                name, member.getRole().name(), member.getSource().name()));
                if (manager && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.kickButton,
                            () -> TownManager.kickMember(player, town, member.getPlayerUuid())).build());
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.promoteButton,
                            () -> TownManager.setRole(player, town, member.getPlayerUuid(),
                                    TownMemberDOO.TownRole.ASSISTANT)).build());
                }
                if (isMayor && member.getRole() == TownMemberDOO.TownRole.ASSISTANT) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.demoteButton,
                            () -> TownManager.setRole(player, town, member.getPlayerUuid(),
                                    TownMemberDOO.TownRole.RESIDENT)).build());
                }
                // the mayor may transfer to ANY member, not just assistants
                if (isMayor && member.getRole() != TownMemberDOO.TownRole.MAYOR) {
                    line.append(actionButton(player, town, member, TextUserInterface.townMembersTuiText.transferButton,
                            () -> TownManager.transferMayor(player, town, member.getPlayerUuid())).build());
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
        public ButtonConfiguration memberItemButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§e{0}",
                List.of(
                        "§7Role: §b{0}",
                        "§7Source: §b{1}",
                        "",
                        "§c▶ Left Click: kick (managers, residents)",
                        "§e▶ Right Click: demote (mayor, assistants)"
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

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townMembersCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townMembersCui.listConfiguration, toIntegrity(args[1], 1));

            for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
                String name = CacheManager.instance.getPlayerName(member.getPlayerUuid());
                ChestButton btn = new ChestButton(ChestUserInterface.townMembersCui.memberItemButton) {
                    @Override
                    public void onClick(ClickType type) {
                        if (!manager) return;
                        // mirror the TUI buttons: residents can be
                        // kicked/promoted, assistants demoted/transferred,
                        // the mayor row is inert (transfer only)
                        TownUI.action(player, () -> {
                            if (type.isLeftClick() && member.getRole() == TownMemberDOO.TownRole.RESIDENT) {
                                TownManager.kickMember(player, town, member.getPlayerUuid());
                            } else if (type.isRightClick()
                                    && member.getRole() == TownMemberDOO.TownRole.ASSISTANT) {
                                TownManager.setRole(player, town, member.getPlayerUuid(),
                                        TownMemberDOO.TownRole.RESIDENT);
                            }
                        }, () -> show(player, town.getName(), "1"));
                    }
                };
                btn = btn.setDisplayNameArgs(name);
                btn = btn.setLoreArgs(member.getRole().name(), member.getSource().name());
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
                    member.getRole().name(), member.getSource().name()));
        }
    }
}
