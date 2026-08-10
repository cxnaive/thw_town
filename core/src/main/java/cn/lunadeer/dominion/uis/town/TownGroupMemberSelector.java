package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.uis.AbstractUI;
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

import java.util.ArrayList;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Select an existing town member to assign or move to a town group. */
public class TownGroupMemberSelector extends AbstractUI {

    public static void show(CommandSender sender, String townName, String groupName, String pageStr) {
        new TownGroupMemberSelector().displayByPreference(sender, townName, groupName, pageStr);
    }

    public static class TownGroupMemberSelectorTuiText extends ConfigurationPart {
        public String title = "Select a member for {0}";
        public String button = "SELECT MEMBER";
        public String backButton = "BACK";
        public String memberLine = "§b{0} §7- Role: §e{1} §7| Current group: §b{2}";
        public String selectButton = "SELECT";
        public String selectDescription = "Assign this member to the group. If assigned elsewhere, they will be moved.";
        public String noCandidates = "Every town member is already assigned to this group.";
    }

    public static class TownGroupMemberSelectorCui extends ConfigurationPart {
        public String title = "§6✦ §eSelect Member for {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i', List.of("<########", "#iiiiiii#", "#iiiiiii#", "#iiiiiii#", "p#######n"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back to Town Group",
                List.of("§e▶ Click to go back"));
        public String memberItemName = "§a➕ §e{0}";
        public List<String> memberItemLore = List.of(
                "§7Role: §b{0}",
                "§7Current group: §b{1}",
                "",
                "§a▶ Click to assign to this group",
                "§8Members in another group will be moved.");
    }

    private record Context(TownDOO town, TownGroupDOO group) {
    }

    private static Context context(Player player, String townName, String groupName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (TownManager.roleOf(town, player.getUniqueId()) == null
                && !cn.lunadeer.dominion.misc.Others.bypassLimit(player)) {
            throw new DominionException(cn.lunadeer.dominion.configuration.Language.townManagerText.notInTown,
                    town.getName());
        }
        TownManager.assertManager(player, town);
        return new Context(town, TownManager.requireTownGroup(town, groupName));
    }

    private static List<TownMemberDOO> candidates(Context context) {
        List<TownMemberDOO> result = new ArrayList<>();
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(context.town().getId())) {
            if (member.getGroupId() != context.group().getId()) result.add(member);
        }
        result.sort((a, b) -> playerName(a).compareToIgnoreCase(playerName(b)));
        return result;
    }

    private static String playerName(TownMemberDOO member) {
        return CacheManager.instance.getPlayerName(member.getPlayerUuid());
    }

    private static String currentGroup(TownMemberDOO member) {
        if (member.getGroupId() <= 0) {
            return Language.townManagerText.townGroupDisplayName;
        }
        TownGroupDOO group = CacheManager.instance.getTownCache().getGroup(member.getGroupId());
        return group == null ? Language.townManagerText.townGroupDisplayName
                : group.getDisplayName();
    }

    private static void assign(Player player, Context context, TownMemberDOO member) {
        TownUI.action(player,
                () -> TownManager.assignTownGroup(player, context.town(), member.getPlayerUuid(), context.group()),
                () -> TownGroupManage.show(player, context.town().getName(), context.group().getName(), "1"));
    }

    private static ListViewButton button(Player player, Context context) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townGroupMemberSelectorTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(player, context.town().getName(), context.group().getName(), pageStr);
            }
        }.needPermission(defaultPermission);
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        Context context = context(player, args[0], args[1]);
        ListView view = ListView.create(10, button(player, context));
        view.title(formatString(TextUserInterface.townGroupMemberSelectorTuiText.title,
                context.group().getDisplayName()));
        view.navigator(Line.create()
                .append(TownGroups.button(player, context.town().getName()).build())
                .append(new FunctionalButton(TextUserInterface.townGroupMemberSelectorTuiText.backButton) {
                    @Override
                    public void function() {
                        TownGroupManage.show(player, context.town().getName(), context.group().getName(), "1");
                    }
                }.needPermission(defaultPermission).build())
                .append(TextUserInterface.townGroupMemberSelectorTuiText.button));
        List<TownMemberDOO> candidates = candidates(context);
        if (candidates.isEmpty()) {
            view.add(Line.create().append(TextUserInterface.townGroupMemberSelectorTuiText.noCandidates));
        }
        for (TownMemberDOO member : candidates) {
            Line line = Line.create().append(formatString(
                    TextUserInterface.townGroupMemberSelectorTuiText.memberLine,
                    playerName(member), TownManager.roleDisplay(member.getRole()), currentGroup(member)));
            line.append(new FunctionalButton(TextUserInterface.townGroupMemberSelectorTuiText.selectButton) {
                @Override
                public void function() {
                    assign(player, context, member);
                }
            }.needPermission(defaultPermission)
                    .setHoverText(TextUserInterface.townGroupMemberSelectorTuiText.selectDescription).build());
            view.add(line);
        }
        view.showOn(player, toIntegrity(args[2], 1));
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        Context context = context(player, args[0], args[1]);
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.townGroupMemberSelectorCui.title,
                context.group().getDisplayName()));
        view.applyListConfiguration(ChestUserInterface.townGroupMemberSelectorCui.listConfiguration,
                toIntegrity(args[2], 1));
        view.setButton(ChestUserInterface.townGroupMemberSelectorCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.townGroupMemberSelectorCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        TownGroupManage.show(player, context.town().getName(), context.group().getName(), "1");
                    }
                });
        for (TownMemberDOO member : candidates(context)) {
            String name = playerName(member);
            ButtonConfiguration head = ButtonConfiguration.createHeadByName(
                    ChestUserInterface.townGroupMemberSelectorCui.listConfiguration.itemSymbol.charAt(0),
                    name,
                    ChestUserInterface.townGroupMemberSelectorCui.memberItemName,
                    ChestUserInterface.townGroupMemberSelectorCui.memberItemLore);
            view.addItem(new ChestButton(head) {
                @Override
                public void onClick(ClickType type) {
                    view.close();
                    assign(player, context, member);
                }
            }.setDisplayNameArgs(name)
                    .setLoreArgs(TownManager.roleDisplay(member.getRole()), currentGroup(member)));
        }
        view.open();
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) {
        // This selector is only meaningful to an in-game player.
    }
}
