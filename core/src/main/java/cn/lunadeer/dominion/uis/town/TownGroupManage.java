package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.misc.DominionException;
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

import java.util.ArrayList;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Member assignment page for one town group. */
public class TownGroupManage extends AbstractUI {

    public static void show(CommandSender sender, String townName, String groupName, String pageStr) {
        new TownGroupManage().displayByPreference(sender, townName, groupName, pageStr);
    }

    public static class TownGroupManageTuiText extends ConfigurationPart {
        public String title = "Town Group: {0}";
        public String button = "GROUP";
        public String internalName = "§7Internal name: §b{0}";
        public String memberLine = "§b{0}";
        public String addButton = "ADD MEMBER";
        public String addHint = "Enter the name of a town member to assign to this group.";
        public String removeButton = "REMOVE";
        public String deleteButton = "DELETE GROUP";
        public String deleteConfirm = "§cClick again within 5 seconds to delete town group {0}.";
        public String noMembers = "This group has no assigned members.";
    }

    public static class TownGroupManageCui extends ConfigurationPart {
        public String title = "§6✦ §eTown Group: {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i', List.of("<######D#", "#iiiiiii#", "#iiiiiii#", "#iiiiiii#", "p######An"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back to Town Groups",
                List.of("§e▶ Click to go back"));
        public ButtonConfiguration addButton = ButtonConfiguration.createMaterial(
                'A', Material.LIME_DYE, "§aAdd Member",
                List.of("§7Assign a town member to this group.", "§a▶ Click to enter a name"));
        public ButtonConfiguration deleteButton = ButtonConfiguration.createMaterial(
                'D', Material.TNT, "§cDelete Group",
                List.of("§7Assigned members will use @town.", "§c▶ Click twice to confirm"));
        public List<String> memberLore = List.of("§7Assigned to this town group.", "", "§c▶ Click to remove");
    }

    private record Context(TownDOO town, TownGroupDOO group, boolean manager) {
    }

    private static Context context(Player player, String townName, String groupName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (TownManager.roleOf(town, player.getUniqueId()) == null
                && !cn.lunadeer.dominion.misc.Others.bypassLimit(player)) {
            throw new DominionException(cn.lunadeer.dominion.configuration.Language.townManagerText.notInTown,
                    town.getName());
        }
        return new Context(town, TownManager.requireTownGroup(town, groupName),
                TownManager.isManager(town, player.getUniqueId())
                        || cn.lunadeer.dominion.misc.Others.bypassLimit(player));
    }

    private static List<TownMemberDOO> members(Context context) {
        List<TownMemberDOO> result = new ArrayList<>();
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(context.town().getId())) {
            if (member.getGroupId() == context.group().getId()) result.add(member);
        }
        result.sort((a, b) -> CacheManager.instance.getPlayerName(a.getPlayerUuid())
                .compareToIgnoreCase(CacheManager.instance.getPlayerName(b.getPlayerUuid())));
        return result;
    }

    private static void addMemberOn(Player player, Context context) {
        new InputterRunner(player, TextUserInterface.townGroupManageTuiText.addHint) {
            @Override
            public void run(String input) {
                var target = CacheManager.instance.getPlayer(input.trim());
                if (target == null) {
                    Notification.error(player,
                            cn.lunadeer.dominion.configuration.Language.townCommandText.playerNotFound, input.trim());
                    return;
                }
                TownUI.action(player,
                        () -> TownManager.assignTownGroup(player, context.town(), target.getUuid(), context.group()),
                        () -> show(player, context.town().getName(), context.group().getName(), "1"));
            }
        };
    }

    private static void deleteGroup(Player player, Context context) {
        cn.lunadeer.dominion.utils.PendingConfirmation.requestConfirm(
                player, "town-group-delete:" + context.group().getId(),
                () -> TownUI.action(player,
                        () -> TownManager.deleteTownGroup(player, context.town(), context.group()),
                        () -> TownGroups.show(player, context.town().getName(), "1")),
                formatString(TextUserInterface.townGroupManageTuiText.deleteConfirm,
                        context.group().getDisplayName()));
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        Context context = context(player, args[0], args[1]);
        ListViewButton pageButton = (ListViewButton) new ListViewButton(
                TextUserInterface.townGroupManageTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(player, context.town().getName(), context.group().getName(), pageStr);
            }
        }.needPermission(defaultPermission);
        ListView view = ListView.create(10, pageButton);
        view.title(formatString(TextUserInterface.townGroupManageTuiText.title,
                context.group().getDisplayName()));
        view.navigator(Line.create().append(TownInfo.button(player, context.town().getName()).build())
                .append(TownGroups.button(player, context.town().getName()).build())
                .append(TextUserInterface.townGroupManageTuiText.button));
        view.add(Line.create().append(formatString(TextUserInterface.townGroupManageTuiText.internalName,
                context.group().getName())));
        if (context.manager()) {
            view.add(Line.create()
                    .append(new FunctionalButton(TextUserInterface.townGroupManageTuiText.addButton) {
                        @Override
                        public void function() {
                            addMemberOn(player, context);
                        }
                    }.needPermission(defaultPermission).build())
                    .append(new FunctionalButton(TextUserInterface.townGroupManageTuiText.deleteButton) {
                        @Override
                        public void function() {
                            deleteGroup(player, context);
                        }
                    }.needPermission(defaultPermission).red().build()));
        }
        List<TownMemberDOO> members = members(context);
        if (members.isEmpty()) {
            view.add(Line.create().append(TextUserInterface.townGroupManageTuiText.noMembers));
        }
        for (TownMemberDOO member : members) {
            String name = CacheManager.instance.getPlayerName(member.getPlayerUuid());
            Line line = Line.create().append(formatString(TextUserInterface.townGroupManageTuiText.memberLine, name));
            if (context.manager()) {
                line.append(new FunctionalButton(TextUserInterface.townGroupManageTuiText.removeButton) {
                    @Override
                    public void function() {
                        TownUI.action(player,
                                () -> TownManager.removeTownGroupAssignment(player, context.town(), member.getPlayerUuid()),
                                () -> show(player, context.town().getName(), context.group().getName(), "1"));
                    }
                }.needPermission(defaultPermission).red().build());
            }
            view.add(line);
        }
        view.showOn(player, toIntegrity(args[2], 1));
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        Context context = context(player, args[0], args[1]);
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.townGroupManageCui.title,
                context.group().getDisplayName()));
        view.applyListConfiguration(ChestUserInterface.townGroupManageCui.listConfiguration, toIntegrity(args[2], 1));
        view.setButton(ChestUserInterface.townGroupManageCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.townGroupManageCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        TownGroups.show(player, context.town().getName(), "1");
                    }
                });
        if (context.manager()) {
            view.setButton(ChestUserInterface.townGroupManageCui.addButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townGroupManageCui.addButton) {
                        @Override
                        public void onClick(ClickType type) {
                            view.close();
                            addMemberOn(player, context);
                        }
                    });
            view.setButton(ChestUserInterface.townGroupManageCui.deleteButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townGroupManageCui.deleteButton) {
                        @Override
                        public void onClick(ClickType type) {
                            deleteGroup(player, context);
                        }
                    });
        }
        for (TownMemberDOO member : members(context)) {
            String name = CacheManager.instance.getPlayerName(member.getPlayerUuid());
            ButtonConfiguration head = ButtonConfiguration.createHeadByName('i', name, name,
                    context.manager() ? ChestUserInterface.townGroupManageCui.memberLore : List.of());
            view.addItem(new ChestButton(head) {
                @Override
                public void onClick(ClickType type) {
                    if (!context.manager()) return;
                    TownUI.action(player,
                            () -> TownManager.removeTownGroupAssignment(player, context.town(), member.getPlayerUuid()),
                            () -> show(player, context.town().getName(), context.group().getName(), "1"));
                }
            });
        }
        view.open();
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        TownGroupDOO group = TownManager.requireTownGroup(town, args[1]);
        Notification.info(sender, TextUserInterface.townGroupManageTuiText.title, group.getDisplayName());
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            if (member.getGroupId() == group.getId()) {
                Notification.info(sender, CacheManager.instance.getPlayerName(member.getPlayerUuid()));
            }
        }
    }
}
