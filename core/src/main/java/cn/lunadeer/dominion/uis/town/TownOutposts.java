package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.MultiServerManager;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.WorldMapManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.dominion.DominionManage;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Outposts of a town, across every backend server (plan §13.3/§13.5).
 * Entries on THIS server open the (town-aware) DominionManage page;
 * entries on other servers display their database-mapped location, support
 * cross-server teleportation, and require switching servers only to manage.
 */
public class TownOutposts extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownOutposts ui = new TownOutposts();
        // cross-server outposts come from the database — prefetch async,
        // render on the main thread (TownUI thread discipline, §13.5)
        Scheduler.runTaskAsync(() -> {
            try {
                ui.prefetched = DominionDOO.selectByTownId(TownManager.requireTown(townName).getId());
            } catch (Exception e) {
                runForSender(sender, () -> Notification.error(sender, e));
                return;
            }
            try {
                ui.prefetchedWorldNames = WorldMapManager.getWorldNames();
            } catch (Exception ignored) {
                // Location labels retain a UUID fallback.
            }
            try {
                ui.prefetchedServerNames = MultiServerManager.instance.getServerNames();
            } catch (Exception ignored) {
                // Server labels retain an ID fallback.
            }
            runForSender(sender, () -> ui.displayByPreference(sender, townName, pageStr));
        });
    }

    private List<DominionDOO> prefetched;
    private Map<Integer, Map<UUID, String>> prefetchedWorldNames = Map.of();
    private Map<Integer, String> prefetchedServerNames = Map.of();

    private static void runForSender(@NotNull CommandSender sender, @NotNull Runnable task) {
        if (sender instanceof Player player) {
            Scheduler.runEntityTask(task, player);
        } else {
            Scheduler.runTask(task);
        }
    }

    private List<DominionDOO> outposts(Integer townId) throws Exception {
        return prefetched != null ? prefetched : DominionDOO.selectByTownId(townId);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townOutpostsTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    private String worldLabel(DominionDOO dominion) {
        if (isLocal(dominion)) {
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
        if (name == null && isLocal(dominion)) {
            name = Configuration.multiServer.serverName;
        }
        return MultiServerManager.formatServerLabel(dominion.getServerId(), name);
    }

    private void notifyRemote(Player player, DominionDOO dominion) {
        Notification.warn(player, TextUserInterface.townOutpostsTuiText.remoteActionHint,
                dominion.getName(), serverLabel(dominion), worldLabel(dominion));
    }

    private static boolean isLocal(DominionDOO dominion) {
        return dominion.getServerId() == Configuration.multiServer.serverId;
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownOutpostsTuiText extends ConfigurationPart {
        public String title = "Outposts of {0}";
        public String button = "OUTPOSTS";
        public String outpostLine = "§b{0} §7- {1} ({2}, server {3})";
        public String contributorTag = " §7by §e{0}";
        public String remoteHint = "REMOTE LOCATION";
        public String remoteActionHint = "Outpost {0} is on server {1}, world {2}. Switch to that server to manage it, or use TELEPORT to travel there.";
        public String teleportButton = "TELEPORT";
    }

    /** owner_uuid on a town outpost is the contributor record (§2.4). */
    private static String contributorTag(DominionDOO outpost) {
        return formatString(TextUserInterface.townOutpostsTuiText.contributorTag,
                CacheManager.instance.getPlayerName(outpost.getOwner()));
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townOutpostsTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townOutpostsTuiText.button));

            for (DominionDOO outpost : outposts(town.getId())) {
                Line line = Line.create();
                // manage entry only with real authority — this page is
                // publicly browsable via the town list
                boolean local = isLocal(outpost);
                if (local && cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(player, outpost)) {
                    line.append(new FunctionalButton(outpost.getName()) {
                        @Override
                        public void function() {
                            DominionManage.show(player, outpost.getName(), "1");
                        }
                    }.needPermission(defaultPermission).build());
                } else {
                    line.append(outpost.getName());
                }
                line.append(formatString(TextUserInterface.townOutpostsTuiText.outpostLine,
                        "", worldLabel(outpost), outpost.getClaimKind(), serverLabel(outpost)));
                line.append(contributorTag(outpost));
                if (!local) {
                    line.append(new FunctionalButton(TextUserInterface.townOutpostsTuiText.remoteHint) {
                        @Override
                        public void function() {
                            notifyRemote(player, outpost);
                        }
                    }.needPermission(defaultPermission).build());
                }
                // TeleportManager handles both local and cross-server travel,
                // and enforces the town-cascade TELEPORT flag before moving.
                line.append(new FunctionalButton(TextUserInterface.townOutpostsTuiText.teleportButton) {
                    @Override
                    public void function() {
                        cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion(player, outpost);
                    }
                }.needPermission(defaultPermission).build());
                view.add(line);
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownOutpostsCui extends ConfigurationPart {
        public String title = "§6✦ §eOutposts of {0} §6✦";
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
        public ButtonConfiguration outpostItemButton = ButtonConfiguration.createMaterial(
                'i', Material.FILLED_MAP, "§6⛏ §e{0}",
                List.of(
                        "§7World: §b{0}",
                        "§7Kind: §b{1}",
                        "§7Server: §b{2}",
                        "§7Contributor: §e{3}",
                        "",
                        "§a▶ Left Click: manage (this server)",
                        "§b▶ Right Click: teleport (this server)"
                )
        );
        public ButtonConfiguration remoteOutpostItemButton = ButtonConfiguration.createMaterial(
                'i', Material.ENDER_EYE, "§6⛏ §e{0}",
                List.of(
                        "§7World: §b{0}",
                        "§7Kind: §b{1}",
                        "§7Server: §b{2}",
                        "§7Contributor: §e{3}",
                        "",
                        "§e▶ Left Click: show location / management server",
                        "§b▶ Right Click: cross-server teleport"
                )
        );
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townOutpostsCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townOutpostsCui.listConfiguration, toIntegrity(args[1], 1));

            for (DominionDOO outpost : outposts(town.getId())) {
                boolean local = isLocal(outpost);
                ButtonConfiguration itemConfiguration = local
                        ? ChestUserInterface.townOutpostsCui.outpostItemButton
                        : ChestUserInterface.townOutpostsCui.remoteOutpostItemButton;
                ChestButton btn = new ChestButton(itemConfiguration) {
                    @Override
                    public void onClick(ClickType type) {
                        if (!local) {
                            if (type.isRightClick()) {
                                cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion(player, outpost);
                            } else {
                                notifyRemote(player, outpost);
                            }
                        } else if (type.isLeftClick()
                                && cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(player, outpost)) {
                            DominionManage.show(player, outpost.getName(), "1");
                        } else if (type.isRightClick()) {
                            // TeleportManager enforces the town-cascade
                            // TELEPORT flag, cooldown and delay.
                            cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion(player, outpost);
                        }
                    }
                };
                btn = btn.setDisplayNameArgs(outpost.getName());
                btn = btn.setLoreArgs(worldLabel(outpost), outpost.getClaimKind(),
                        serverLabel(outpost),
                        CacheManager.instance.getPlayerName(outpost.getOwner()));
                view = view.addItem(btn);
            }
            view.setButton(ChestUserInterface.townOutpostsCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townOutpostsCui.backButton) {
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
        for (DominionDOO outpost : outposts(town.getId())) {
            Notification.info(sender, formatString(TextUserInterface.townOutpostsTuiText.outpostLine,
                    outpost.getName(), worldLabel(outpost), outpost.getClaimKind(), serverLabel(outpost))
                    + contributorTag(outpost));
        }
    }
}
