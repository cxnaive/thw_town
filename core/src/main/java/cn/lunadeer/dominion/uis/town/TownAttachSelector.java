package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.MultiServerManager;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.WorldMapManager;
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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.misc.Others.bypassLimit;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * UI for selecting which personal dominions to attach to the town.
 * Uses asynchronous direct database queries for cross-server consistency.
 */
public class TownAttachSelector extends AbstractUI {

    // Track player selections: player UUID -> set of dominion IDs
    private static final Map<UUID, Set<Integer>> selections = new ConcurrentHashMap<>();

    private List<DominionDOO> prefetchedAttachable = List.of();
    private Map<Integer, Map<UUID, String>> prefetchedWorldNames = Map.of();
    private Map<Integer, String> prefetchedServerNames = Map.of();

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownAttachSelector ui = new TownAttachSelector();
        if (!(sender instanceof Player player)) {
            ui.displayByPreference(sender, townName, pageStr);
            return;
        }
        TownUI.prefetchThenRender(player, () -> {
            requireAccessibleTown(player, townName);
            ui.prefetchedAttachable = DominionDOO.selectByOwner(player.getUniqueId()).stream()
                    .filter(d -> !d.isTownOutpost())
                    .toList();
            try {
                ui.prefetchedWorldNames = WorldMapManager.getWorldNames();
            } catch (Exception ignored) {
                // World labels have a UUID fallback; a missing mapping must
                // not prevent the player from selecting an attachable land.
            }
            try {
                ui.prefetchedServerNames = MultiServerManager.instance.getServerNames();
            } catch (Exception ignored) {
                // Server labels have an ID fallback for the same reason.
            }
        }, () -> ui.displayByPreference(player, townName, pageStr));
    }

    private static TownDOO requireAccessibleTown(Player player, String townName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (TownManager.roleOf(town, player.getUniqueId()) == null && !bypassLimit(player)) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    cn.lunadeer.dominion.configuration.Language.townManagerText.notInTown,
                    town.getName());
        }
        return town;
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townAttachSelectorTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    private String worldLabel(DominionDOO dominion) {
        if (dominion.getServerId() == Configuration.multiServer.serverId) {
            World world = Bukkit.getWorld(dominion.getWorldUid());
            if (world != null) {
                return world.getName();
            }
        }
        String mappedName = prefetchedWorldNames
                .getOrDefault(dominion.getServerId(), Map.of())
                .get(dominion.getWorldUid());
        return mappedName != null
                ? mappedName
                : "#" + dominion.getWorldUid().toString().substring(0, 8);
    }

    private String serverLabel(DominionDOO dominion) {
        String name = prefetchedServerNames.get(dominion.getServerId());
        if (name == null && dominion.getServerId() == Configuration.multiServer.serverId) {
            name = Configuration.multiServer.serverName;
        }
        return MultiServerManager.formatServerLabel(dominion.getServerId(), name);
    }

    private String locationLabel(DominionDOO dominion) {
        String world = worldLabel(dominion);
        return dominion.getServerId() == Configuration.multiServer.serverId
                ? world
                : world + " @ " + serverLabel(dominion);
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
            TownDOO town = requireAccessibleTown(player, args[0]);
            int page = toIntegrity(args[1], 1);

            List<DominionDOO> attachable = prefetchedAttachable;

            if (attachable.isEmpty()) {
                Notification.info(player, TextUserInterface.townAttachSelectorTuiText.noDominions);
                return;
            }

            Set<Integer> selected = selections.computeIfAbsent(player.getUniqueId(),
                    k -> ConcurrentHashMap.newKeySet());

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
                                locationLabel(dominion)));

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
                        "#########",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "c##p#n#C#"
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
            TownDOO town = requireAccessibleTown(player, args[0]);

            List<DominionDOO> attachable = prefetchedAttachable;

            if (attachable.isEmpty()) {
                Notification.info(player, TextUserInterface.townAttachSelectorTuiText.noDominions);
                return;
            }

            Set<Integer> selected = selections.computeIfAbsent(player.getUniqueId(),
                    k -> ConcurrentHashMap.newKeySet());

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
                        locationLabel(dominion));
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
        if (selected.isEmpty()) {
            Notification.warn(player, TextUserInterface.townAttachSelectorTuiText.noneSelected);
            selections.remove(player.getUniqueId());
            TownInfo.show(player, town.getName(), "1");
            return;
        }
        Set<Integer> selectedSnapshot = new HashSet<>(selected);
        selections.remove(player.getUniqueId());
        TownUI.action(player, () -> {
            TownDOO currentTown = requireAccessibleTown(player, town.getName());
            List<DominionDOO> candidates = new ArrayList<>();
            for (Integer dominionId : selectedSnapshot) {
                // Re-query and re-check ownership: ownership can change while
                // the selector is open, and stale UI state must never attach
                // another player's land.
                DominionDOO dominion = DominionDOO.select(dominionId);
                if (dominion == null || dominion.isTownOutpost()
                        || !dominion.getOwner().equals(player.getUniqueId())) {
                    continue;
                }
                candidates.add(dominion);
            }
            int count = TownManager.attachOwnedOutposts(currentTown, candidates,
                    player.getUniqueId(), player.getUniqueId()).size();
            Notification.info(player, TextUserInterface.townAttachSelectorTuiText.attachSuccess,
                    count, currentTown.getName());
        }, () -> TownInfo.show(player, town.getName(), "1"));
    }
}
