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

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Town-level permission groups shared by every outpost of a town. */
public class TownGroups extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        new TownGroups().displayByPreference(sender, townName, pageStr);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townGroupsTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    public static class TownGroupsTuiText extends ConfigurationPart {
        public String title = "Town Groups of {0}";
        public String button = "GROUPS";
        public String createButton = "CREATE";
        public String createHint = "Enter: <internal name> <display name>, for example: builders 建筑组. The internal name may use lowercase letters, numbers, '_' or '-'.";
        public String createDisplayHint = "Now enter the display name, for example: 建筑组. Chinese and spaces are supported; enter '-' to use the internal name.";
        public String groupLine = "§b{0} §7({1}) - members: §b{2}";
        public String openButton = "MANAGE";
        public String openDescription = "View members and manage this town group.";
        public String noGroups = "This town has no custom groups.";
    }

    public static class TownGroupsCui extends ConfigurationPart {
        public String title = "§6✦ §eTown Groups of {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i', List.of("<########", "#iiiiiii#", "#iiiiiii#", "#iiiiiii#", "p######An"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back to Town",
                List.of("§e▶ Click to go back"));
        public ButtonConfiguration createButton = ButtonConfiguration.createMaterial(
                'A', Material.LIME_DYE, "§aCreate Town Group",
                List.of("§7Create a group mirrored to every outpost.",
                        "§7Example: §fbuilders 建筑组", "§a▶ Click to enter names"));
        public ButtonConfiguration groupItemButton = ButtonConfiguration.createMaterial(
                'i', Material.CHEST, "§e{0}",
                List.of("§7Internal name: §f{0}", "§7Members: §b{1}", "", "§e▶ Click to manage"));
    }

    private static TownDOO requireVisibleTown(Player player, String townName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (TownManager.roleOf(town, player.getUniqueId()) == null
                && !cn.lunadeer.dominion.misc.Others.bypassLimit(player)) {
            throw new DominionException(cn.lunadeer.dominion.configuration.Language.townManagerText.notInTown,
                    town.getName());
        }
        return town;
    }

    private static int memberCount(TownDOO town, TownGroupDOO group) {
        int count = 0;
        for (TownMemberDOO member : CacheManager.instance.getTownCache().getMembersOf(town.getId())) {
            if (member.getGroupId() == group.getId()) count++;
        }
        return count;
    }

    public static void createOn(Player player, String townName) {
        new InputterRunner(player, TextUserInterface.townGroupsTuiText.createHint) {
            @Override
            public void run(String input) {
                String trimmed = input.trim();
                if (trimmed.isEmpty()) {
                    Notification.warn(player, TextUserInterface.townGroupsTuiText.createHint);
                    return;
                }
                // Keep the old one-line input form compatible for command/UI users
                // who already enter "internal-name display name".
                String[] tokens = trimmed.split("\\s+", 2);
                if (tokens.length == 2) {
                    create(player, townName, tokens[0], tokens[1]);
                    return;
                }
                new InputterRunner(player, TextUserInterface.townGroupsTuiText.createDisplayHint) {
                    @Override
                    public void run(String displayInput) {
                        create(player, townName, tokens[0], displayInput);
                    }
                };
            }
        };
    }

    private static void create(Player player, String townName, String internalName, String displayInput) {
        String displayName = displayInput == null || displayInput.isBlank()
                || displayInput.trim().equals("-") ? null : displayInput.trim();
        TownUI.action(player,
                () -> TownManager.createTownGroup(player, TownManager.requireTown(townName),
                        internalName, displayName),
                () -> show(player, townName, "1"));
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        TownDOO town = requireVisibleTown(player, args[0]);
        boolean manager = TownManager.isManager(town, player.getUniqueId())
                || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
        List<TownGroupDOO> groups = TownManager.groupsOf(town);
        ListView view = ListView.create(10, button(player, town.getName()));
        view.title(formatString(TextUserInterface.townGroupsTuiText.title, town.getName()));
        view.navigator(Line.create().append(TownInfo.button(player, town.getName()).build())
                .append(TextUserInterface.townGroupsTuiText.button));
        if (manager) {
            view.add(Line.create().append(new FunctionalButton(TextUserInterface.townGroupsTuiText.createButton) {
                @Override
                public void function() {
                    createOn(player, town.getName());
                }
            }.needPermission(defaultPermission).build()));
        }
        if (groups.isEmpty()) {
            view.add(Line.create().append(TextUserInterface.townGroupsTuiText.noGroups));
        }
        for (TownGroupDOO group : groups) {
            Line line = Line.create().append(formatString(TextUserInterface.townGroupsTuiText.groupLine,
                    group.getDisplayName(), group.getName(), memberCount(town, group)));
            line.append(new FunctionalButton(TextUserInterface.townGroupsTuiText.openButton) {
                @Override
                public void function() {
                    TownGroupManage.show(player, town.getName(), group.getName(), "1");
                }
            }.needPermission(defaultPermission)
                    .setHoverText(TextUserInterface.townGroupsTuiText.openDescription).build());
            view.add(line);
        }
        view.showOn(player, toIntegrity(args[1], 1));
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        TownDOO town = requireVisibleTown(player, args[0]);
        boolean manager = TownManager.isManager(town, player.getUniqueId())
                || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.townGroupsCui.title, town.getName()));
        view.applyListConfiguration(ChestUserInterface.townGroupsCui.listConfiguration, toIntegrity(args[1], 1));
        view.setButton(ChestUserInterface.townGroupsCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.townGroupsCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        TownInfo.show(player, town.getName(), "1");
                    }
                });
        if (manager) {
            view.setButton(ChestUserInterface.townGroupsCui.createButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townGroupsCui.createButton) {
                        @Override
                        public void onClick(ClickType type) {
                            view.close();
                            createOn(player, town.getName());
                        }
                    });
        }
        for (TownGroupDOO group : TownManager.groupsOf(town)) {
            view.addItem(new ChestButton(ChestUserInterface.townGroupsCui.groupItemButton) {
                @Override
                public void onClick(ClickType type) {
                    TownGroupManage.show(player, town.getName(), group.getName(), "1");
                }
            }.setDisplayNameArgs(group.getDisplayName())
                    .setLoreArgs(group.getName(), memberCount(town, group)));
        }
        view.open();
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        Notification.info(sender, TextUserInterface.townGroupsTuiText.title, town.getName());
        for (TownGroupDOO group : TownManager.groupsOf(town)) {
            Notification.info(sender, TextUserInterface.townGroupsTuiText.groupLine,
                    group.getDisplayName(), group.getName(), memberCount(town, group));
        }
    }
}
