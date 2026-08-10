package cn.lunadeer.dominion.uis.dominion.manage.group;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.api.dtos.PlayerDTO;
import cn.lunadeer.dominion.commands.GroupCommand;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
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
import static cn.lunadeer.dominion.doos.MemberDOO.selectByDominionId;
import static cn.lunadeer.dominion.misc.Asserts.assertDominionAdmin;
import static cn.lunadeer.dominion.misc.Converts.*;
import static cn.lunadeer.dominion.utils.Misc.formatString;

public class SelectMember extends AbstractUI {

    public static void show(CommandSender sender, String dominionName, String groupName, String backPageStr, String pageStr) {
        new SelectMember().displayByPreference(sender, dominionName, groupName, backPageStr, pageStr);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class SelectMemberTuiText extends ConfigurationPart {
        public String title = "Select Member";
        public String description = "Select a member to add to the group.";
        public String back = "BACK";
    }

    public static ListViewButton button(CommandSender sender, String dominionName, String groupName, String backPageStr) {
        return (ListViewButton) new ListViewButton("+") {
            @Override
            public void function(String page) {
                show(sender, dominionName, groupName, backPageStr, page);
            }
        }.needPermission(defaultPermission).green().setHoverText(TextUserInterface.selectMemberTuiText.description);
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        String dominionName = args[0];
        String groupName = args[1];
        String backPageStr = args[2];
        String pageStr = args[3];

        DominionDTO dominion = toDominionDTO(dominionName);
        assertDominionAdmin(player, dominion);
        int page = toIntegrity(pageStr);

        ListView view = ListView.create(10, button(player, dominionName, groupName, backPageStr));
        view.title(TextUserInterface.selectMemberTuiText.title);
        Line sub = Line.create().append(new FunctionalButton(TextUserInterface.selectMemberTuiText.back) {
            @Override
            public void function() {
                GroupList.show(player, dominionName, backPageStr);
            }
        }.needPermission(defaultPermission).build());
        view.subtitle(sub);

        // get data from database directly because cache update may not be in time
        List<MemberDTO> members = new ArrayList<>(selectByDominionId(dominion.getId()));
        for (MemberDTO member : members) {
            if (member.getGroupId() != -1) {
                continue;
            }
            PlayerDTO p = toPlayerDTO(member.getPlayerUUID());
            view.add(Line.create()
                    .append(new FunctionalButton(p.getLastKnownName()) {
                        @Override
                        public void function() {
                            GroupCommand.addMember(player, dominionName, groupName, p.getLastKnownName());
                        }
                    }.needPermission(defaultPermission).build()));
        }
        view.showOn(player, page);
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class SelectMemberCui extends ConfigurationPart {
        public String title = "§6✦ §5§lSelect Member for Group {0} §6✦";
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

        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE,
                "§c« Back to Group Manage",
                List.of(
                        "§7Return to the group manage",
                        "§7without selecting a member.",
                        "",
                        "§e▶ Click to go back"
                )
        );

        public String memberItemName = "§a➕ §2{0}";
        public List<String> memberItemLore = List.of(
                "§7Add this member to the group.",
                "",
                "§7Player: §e{0}",
                "",
                "§2▶ Click to add to group",
                "",
                "§8This member is not in any group."
        );
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        String dominionName = args[0];
        String groupName = args[1];
        String backPageStr = args[2];

        DominionDTO dominion = toDominionDTO(dominionName);
        assertDominionAdmin(player, dominion);
        cn.lunadeer.dominion.api.dtos.GroupDTO group = toGroupDTO(dominion, groupName);

        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.selectMemberCui.title, group.getNameColoredBukkit()));
        view.applyListConfiguration(ChestUserInterface.selectMemberCui.listConfiguration, toIntegrity(args[3]));

        view.setButton(ChestUserInterface.selectMemberCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.selectMemberCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        GroupList.show(player, dominionName, backPageStr);
                    }
                }
        );

        // get data from database directly because cache update may not be in time
        List<MemberDTO> members = new ArrayList<>(selectByDominionId(dominion.getId()));
        for (MemberDTO member : members) {
            if (member.getGroupId() != -1) {
                continue;
            }
            PlayerDTO p = toPlayerDTO(member.getPlayerUUID());

            ButtonConfiguration item = ButtonConfiguration.createHeadByName(
                    ChestUserInterface.selectMemberCui.listConfiguration.itemSymbol.charAt(0),
                    p.getLastKnownName(),
                    ChestUserInterface.selectMemberCui.memberItemName,
                    ChestUserInterface.selectMemberCui.memberItemLore
            );

            ChestButton memberChest = new ChestButton(item) {
                @Override
                public void onClick(ClickType type) {
                    GroupCommand.addMember(player, dominionName, groupName, p.getLastKnownName());
                    GroupManage.show(player, dominionName, groupName, "1");
                }
            }.setDisplayNameArgs(p.getLastKnownName()).setLoreArgs(p.getLastKnownName());
            view.addItem(memberChest);
        }

        view.open();
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
    }
}
