package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
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
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * All towns, paginated (plan §13.3). Read-only browser for players; the
 * admin mode (same page, admin permission) doubles as the "manage all
 * towns" entry — town detail pages render admin arbitration buttons.
 * Towns are cross-server entities, so this reads the local TownCache
 * replica (60s convergence, §7.4) rather than any per-server cache.
 */
public class TownList extends AbstractUI {

    public static void show(CommandSender sender, String pageStr) {
        new TownList().displayByPreference(sender, pageStr);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownListTuiText extends ConfigurationPart {
        public String title = "All Towns";
        public String description = "List all towns.";
        public String button = "TOWNS";
        public String townLine = "§b{0} §7- mayor: {1}, members: {2}, balance: {3}";
    }

    public static ListViewButton button(CommandSender sender) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townListTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    @Override
    protected void showTUI(Player player, String... args) {
        int page = toIntegrity(args[0], 1);
        ListView view = ListView.create(10, button(player));
        view.title(TextUserInterface.townListTuiText.title);
        view.navigator(Line.create()
                .append(MainMenu.button(player).build())
                .append(TextUserInterface.townListTuiText.button)
                .append(TownGuide.button(player, TownGuide.Topic.CONTENTS, null,
                        () -> show(player, args[0])).build()));
        for (TownDOO town : CacheManager.instance.getTownCache().getAllTowns()) {
            Line line = Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(formatString(TextUserInterface.townListTuiText.townLine,
                            town.getName(),
                            CacheManager.instance.getPlayerName(town.getMayorUuid()),
                            CacheManager.instance.getTownCache().getMembersOf(town.getId()).size(),
                            town.getBalance()));
            view.add(line);
        }
        view.showOn(player, page);
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownListCui extends ConfigurationPart {
        public String title = "§6✦ §e§lAll Towns §6✦";
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
        public ButtonConfiguration townItemButton = ButtonConfiguration.createMaterial(
                'i', Material.GRASS_BLOCK, "§6⛏ §e{0}",
                List.of(
                        "§7Mayor: §b{0}",
                        "§7Members: §b{1}",
                        "§7Balance: §b{2}",
                        "",
                        "§a▶ Left Click: view town"
                )
        );
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE,
                "§c« Back to Main Menu",
                List.of("§e▶ Click to go back")
        );
    }

    @Override
    protected void showCUI(Player player, String... args) {
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(ChestUserInterface.townListCui.title);
        view.applyListConfiguration(ChestUserInterface.townListCui.listConfiguration, toIntegrity(args[0]));

        for (TownDOO town : CacheManager.instance.getTownCache().getAllTowns()) {
            ChestButton btn = new ChestButton(ChestUserInterface.townListCui.townItemButton) {
                @Override
                public void onClick(ClickType type) {
                    if (type.isLeftClick()) {
                        TownInfo.show(player, town.getName(), "1");
                    }
                }
            };
            btn = btn.setDisplayNameArgs(town.getName());
            btn = btn.setLoreArgs(
                    CacheManager.instance.getPlayerName(town.getMayorUuid()),
                    String.valueOf(CacheManager.instance.getTownCache().getMembersOf(town.getId()).size()),
                    String.valueOf(town.getBalance()));
            view = view.addItem(btn);
        }

        view.setButton(ChestUserInterface.townListCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.townListCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        MainMenu.show(player, "1");
                    }
                });
        TownGuide.addHelpButton(view, player, TownGuide.Topic.CONTENTS, null,
                () -> show(player, args[0]));
        view.open();
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) {
        for (TownDOO town : CacheManager.instance.getTownCache().getAllTowns()) {
            Notification.info(sender, formatString(TextUserInterface.townListTuiText.townLine,
                    town.getName(),
                    CacheManager.instance.getPlayerName(town.getMayorUuid()),
                    CacheManager.instance.getTownCache().getMembersOf(town.getId()).size(),
                    town.getBalance()));
        }
    }
}
