package cn.lunadeer.dominion.uis.dominion.manage.group;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.commands.GroupCommand;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.inputters.RenameGroupInputter;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestListView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.scui.configuration.ListViewConfiguration;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

import static cn.lunadeer.dominion.misc.Asserts.assertDominionAdmin;
import static cn.lunadeer.dominion.misc.Converts.*;
import static cn.lunadeer.dominion.utils.Misc.formatString;

public class GroupManage extends AbstractUI {

    public static void show(CommandSender sender, String dominionName, String groupName, String pageStr) {
        new GroupManage().displayByPreference(sender, dominionName, groupName, pageStr);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showTUI(Player player, String... args) {
        throw new UnsupportedOperationException("GroupManage does not support TUI.");
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class GroupManageCUI extends ConfigurationPart {
        public String title = "§6✦ §d§lManage Group {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<##P#R#D#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "p#######n"
                )
        );

        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE,
                "§c« Back to Group List",
                List.of(
                        "§7Return to the group list",
                        "§7to manage other groups.",
                        "",
                        "§e▶ Click to go back"
                )
        );

        public ButtonConfiguration groupFlagsButton = ButtonConfiguration.createMaterial(
                'P', Material.BAMBOO_SIGN,
                "§6📋 §e§lGroup Permissions",
                List.of(
                        "§7Configure what this group",
                        "§7can and cannot do in the dominion.",
                        "",
                        "§e▶ Click to edit permissions",
                        "§8  Set build, interact, etc...",
                        "",
                        "§7Type: §6Permission Settings"
                )
        );

        public ButtonConfiguration renameGroupButton = ButtonConfiguration.createMaterial(
                'R', Material.NAME_TAG,
                "§b✏ §3§lRename Group",
                List.of(
                        "§7Change the display name",
                        "§7of this permission group.",
                        "",
                        "§3▶ Click to rename group",
                        "§8  Choose a new creative name!",
                        "",
                        "§7Current: §f{0}"
                )
        );

        public ButtonConfiguration deleteGroupButton = ButtonConfiguration.createMaterial(
                'D', Material.TNT,
                "§c💥 §4§lDelete Group",
                List.of(
                        "§c⚠ §7This action cannot be undone!",
                        "§7All members will be moved to",
                        "§7the default group automatically.",
                        "",
                        "§4▶ Click to delete group",
                        "",
                        "§8Think twice before proceeding..."
                )
        );

        public ButtonConfiguration addMemberButton = ButtonConfiguration.createMaterial(
                'i', Material.LIME_DYE,
                "§a➕ §2§lAdd Member",
                List.of(
                        "§7Select a member from your",
                        "§7dominion to add to this group.",
                        "",
                        "§2▶ Click to select player",
                        "§8  Grant them group permissions!",
                        "",
                        "§7Action: §aAdd to Group"
                )
        );

        public List<String> playerHeadItemLore = List.of(
                "§7Member of this permission group",
                "",
                "§c▶ Click to remove from group",
                "§8  They'll return to default group"
        );

        public ButtonConfiguration townGroupHintButton = ButtonConfiguration.createMaterial(
                'i', Material.BOOK,
                "§6⛨ §eDefault Member Permissions",
                List.of(
                        "§7Members without a custom group use",
                        "§7this group's permissions on this land.",
                        "§7Assign groups from Town → Member Permissions.",
                        "",
                        "§8Managed by the town system."
                )
        );

        public ButtonConfiguration townGroupMirrorHintButton = ButtonConfiguration.createMaterial(
                'i', Material.BOOK,
                "§6⛨ §eTown-managed Group",
                List.of(
                        "§7Membership is shared by the town and",
                        "§7cannot be changed from this outpost.",
                        "§7Use the town group page to assign members.",
                        "",
                        "§8Permission flags remain editable here."
                )
        );
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        DominionDTO dominion = toDominionDTO(args[0]);
        assertDominionAdmin(player, dominion);
        GroupDTO group = toGroupDTO(dominion, args[1]);
        boolean isTownGroup = cn.lunadeer.dominion.managers.TownManager
                .isTownManagedGroup(group, dominion);
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.groupManageCUI.title,
                cn.lunadeer.dominion.managers.TownManager.townGroupDisplayName(group)));
        view.applyListConfiguration(ChestUserInterface.groupManageCUI.listConfiguration, toIntegrity(args[2]));

        view.setButton(ChestUserInterface.groupManageCUI.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.groupManageCUI.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        GroupList.show(player, args[0], "1");
                    }
                }
        );

        view.setButton(ChestUserInterface.groupManageCUI.groupFlagsButton.getSymbol(),
                new ChestButton(ChestUserInterface.groupManageCUI.groupFlagsButton) {
                    @Override
                    public void onClick(ClickType type) {
                        GroupFlags.show(player, args[0], args[1], "1");
                    }
                }
        );

        if (!isTownGroup) {
            view.setButton(ChestUserInterface.groupManageCUI.renameGroupButton.getSymbol(),
                    new ChestButton(ChestUserInterface.groupManageCUI.renameGroupButton) {
                        @Override
                        public void onClick(ClickType type) {
                            RenameGroupInputter.createOn(player, args[0], args[1]);
                            view.close();
                        }
                    }
            );

            view.setButton(ChestUserInterface.groupManageCUI.deleteGroupButton.getSymbol(),
                    new ChestButton(ChestUserInterface.groupManageCUI.deleteGroupButton) {
                        @Override
                        public void onClick(ClickType type) {
                            GroupCommand.deleteGroup(player, args[0], args[1], "1");
                        }
                    }
            );
        }

        // the built-in @town group has no member list: town members hold
        // its flags implicitly (TownManager.townMemberFlagValue). Show an
        // explainer instead of a meaningless empty list / add-member button
        // (a member row added here would be archived away on re-attach).
        if (isTownGroup) {
            ButtonConfiguration hint = group.getTownGroupId() == null
                    ? ChestUserInterface.groupManageCUI.townGroupHintButton
                    : ChestUserInterface.groupManageCUI.townGroupMirrorHintButton;
            view.addItem(new ChestButton(hint) {
                @Override
                public void onClick(ClickType type) {
                    // info only
                }
            });
        } else {
            view.addItem(new ChestButton(ChestUserInterface.groupManageCUI.addMemberButton) {
                @Override
                public void onClick(ClickType type) {
                    SelectMember.show(player, dominion.getName(), args[1], "1", "1");
                }
            });
        }

        for (int i = 0; !isTownGroup && i < group.getMembers().size(); i++) {
            MemberDTO m = group.getMembers().get(i);
            Integer page = (int) Math.ceil((double) (i + 2) / view.getPageSize());
            ButtonConfiguration item = ButtonConfiguration.createHeadByName(
                    ChestUserInterface.groupManageCUI.listConfiguration.itemSymbol.charAt(0),
                    m.getPlayer().getLastKnownName(),
                    m.getPlayer().getLastKnownName(),
                    ChestUserInterface.groupManageCUI.playerHeadItemLore
            );
            view.addItem(new ChestButton(item) {
                @Override
                public void onClick(ClickType type) {
                    GroupCommand.removeMember(player, dominion.getName(), args[1], m.getPlayer().getLastKnownName(), page.toString());
                    GroupManage.show(player, dominion.getName(), args[1], page.toString());
                }
            });
        }

        view.open();
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
    }
}
