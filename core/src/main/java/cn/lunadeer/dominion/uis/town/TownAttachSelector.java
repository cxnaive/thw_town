package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
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
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.*;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * UI for selecting which personal dominions to attach to the town.
 * Uses direct database queries for cross-server consistency.
 */
public class TownAttachSelector extends AbstractUI {

    // Track player selections: player UUID -> set of dominion IDs
    private static final Map<UUID, Set<Integer>> selections = new HashMap<>();

    public static void show(CommandSender sender, String townName, String pageStr) {
        new TownAttachSelector().displayByPreference(sender, townName, pageStr);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townAttachSelectorTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownAttachSelectorTuiText extends ConfigurationPart {
        public String title = "Attach Dominions to {0}";
        public String button = "SELECT DOMINIONS";
        public String dominionLine = "{0} §b{1} §7({2}, {3})";
        public String selected = "[✓]";
        public String unselected = "[ ]";
        public String toggleButton = "TOGGLE";
        public String confirmButton = "CONFIRM";
        public String cancelButton = "CANCEL";
        public String attachSuccess = "Attached {0} dominion(s) to town {1}.";
        public String noneSelected = "No dominions selected.";
        public String noDominions = "You have no personal dominions to attach.";
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);

            // Query database directly for cross-server consistency
            List<DominionDOO> attachable = DominionDOO.selectByOwner(player.getUniqueId()).stream()
                    .filter(d -> !d.isTownOutpost())
                    .toList();

            if (attachable.isEmpty()) {
                Notification.info(player, TextUserInterface.townAttachSelectorTuiText.noDominions);
                return;
            }

            Set<Integer> selected = selections.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townAttachSelectorTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townAttachSelectorTuiText.button));

            for (DominionDOO dominion : attachable) {
                boolean isSelected = selected.contains(dominion.getId());
                String mark = isSelected ? TextUserInterface.townAttachSelectorTuiText.selected
                        : TextUserInterface.townAttachSelectorTuiText.unselected;
                
                Line line = Line.create()
                        .append(formatString(TextUserInterface.townAttachSelectorTuiText.dominionLine,
                                mark, dominion.getName(),
                                dominion.getClaimKind(),
                                dominion.getWorld().getName()));

                line.append(new FunctionalButton(TextUserInterface.townAttachSelectorTuiText.toggleButton) {
                    @Override
                    public void function() {
                        if (selected.contains(dominion.getId())) {
                            selected.remove(dominion.getId());
                        } else {
                            selected.add(dominion.getId());
                        }
                        show(player, town.getName(), String.valueOf(page));
                    }
                }.needPermission(defaultPermission).build());

                view.add(line);
            }

            // Control buttons
            Line controls = Line.create();
            controls.append(new FunctionalButton(TextUserInterface.townAttachSelectorTuiText.confirmButton) {
                @Override
                public void function() {
                    confirmAttach(player, town, selected);
                }
            }.needPermission(defaultPermission).build());
            
            controls.append(new FunctionalButton(TextUserInterface.townAttachSelectorTuiText.cancelButton) {
                @Override
                public void function() {
                    selections.remove(player.getUniqueId());
                    TownInfo.show(player, town.getName(), "1");
                }
            }.needPermission(defaultPermission).build());
            
            view.add(controls);
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownAttachSelectorCui extends ConfigurationPart {
        public String title = "§6✦ §eAttach to {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<#######",
                        "#iiiiiii",
                        "#iiiiiii",
                        "#iiiiiii",
                        "c#####C>"
                )
        );
        public ButtonConfiguration selectedItemButton = ButtonConfiguration.createMaterial(
                'i', Material.GRASS_BLOCK, "§a✓ §e{0}",
                List.of(
                        "§7Kind: §b{0}",
                        "§7World: §b{1}",
                        "",
                        "§a§lSELECTED",
                        "§c▶ Click to unselect"
                )
        );
        public ButtonConfiguration unselectedItemButton = ButtonConfiguration.createMaterial(
                'i', Material.RED_STAINED_GLASS, "§c✗ §e{0}",
                List.of(
                        "§7Kind: §b{0}",
                        "§7World: §b{1}",
                        "",
                        "§7Not selected",
                        "§a▶ Click to select"
                )
        );
        public ButtonConfiguration confirmButton = ButtonConfiguration.createMaterial(
                'C', Material.LIME_CONCRETE, "§a§lCONFIRM",
                List.of("§7Attach selected dominions", "§a▶ Click to confirm"));
        public ButtonConfiguration cancelButton = ButtonConfiguration.createMaterial(
                'c', Material.RED_CONCRETE, "§c§lCANCEL",
                List.of("§7Discard selection", "§c▶ Click to cancel"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);

            // Query database directly for cross-server consistency
            List<DominionDOO> attachable = DominionDOO.selectByOwner(player.getUniqueId()).stream()
                    .filter(d -> !d.isTownOutpost())
                    .toList();

            if (attachable.isEmpty()) {
                Notification.info(player, TextUserInterface.townAttachSelectorTuiText.noDominions);
                return;
            }

            Set<Integer> selected = selections.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townAttachSelectorCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townAttachSelectorCui.listConfiguration,
                    toIntegrity(args[1], 1));

            for (DominionDOO dominion : attachable) {
                boolean isSelected = selected.contains(dominion.getId());
                ButtonConfiguration config = isSelected
                        ? ChestUserInterface.townAttachSelectorCui.selectedItemButton
                        : ChestUserInterface.townAttachSelectorCui.unselectedItemButton;

                ChestButton btn = new ChestButton(config) {
                    @Override
                    public void onClick(ClickType type) {
                        if (selected.contains(dominion.getId())) {
                            selected.remove(dominion.getId());
                        } else {
                            selected.add(dominion.getId());
                        }
                        show(player, town.getName(), "1");
                    }
                };
                btn = btn.setDisplayNameArgs(dominion.getName());
                btn = btn.setLoreArgs(
                        dominion.getClaimKind(),
                        dominion.getWorld().getName());
                view = view.addItem(btn);
            }

            final ChestListView viewFinal = view;
            view.setButton(ChestUserInterface.townAttachSelectorCui.confirmButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townAttachSelectorCui.confirmButton) {
                        @Override
                        public void onClick(ClickType type) {
                            viewFinal.close();
                            confirmAttach(player, town, selected);
                        }
                    });

            view.setButton(ChestUserInterface.townAttachSelectorCui.cancelButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townAttachSelectorCui.cancelButton) {
                        @Override
                        public void onClick(ClickType type) {
                            selections.remove(player.getUniqueId());
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
        Notification.error(sender, "This command requires a player (TUI/CUI).");
    }

    // ===== Helper =====

    private void confirmAttach(Player player, TownDOO town, Set<Integer> selected) {
        try {
            if (selected.isEmpty()) {
                Notification.warn(player, TextUserInterface.townAttachSelectorTuiText.noneSelected);
                selections.remove(player.getUniqueId());
                TownInfo.show(player, town.getName(), "1");
                return;
            }

            int count = 0;
            for (Integer dominionId : selected) {
                // Re-query from database to ensure fresh state
                DominionDOO dominion = DominionDOO.select(dominionId);
                if (dominion != null && !dominion.isTownOutpost()) {
                    // Use TownManager.attachOutpost for complete attachment contract:
                    // merges members, attaches to town, ensures town member group
                    TownManager.attachOutpost(town, dominion, player.getUniqueId());
                    count++;
                }
            }

            selections.remove(player.getUniqueId());
            CacheManager.instance.reloadCache();
            Notification.info(player, TextUserInterface.townAttachSelectorTuiText.attachSuccess,
                    count, town.getName());
            TownInfo.show(player, town.getName(), "1");
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }
}
