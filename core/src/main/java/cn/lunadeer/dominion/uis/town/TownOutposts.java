package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.dominion.DominionManage;
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

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Outposts of a town, across every backend server (plan §13.3/§13.5).
 * Entries on THIS server open the (town-aware) DominionManage page;
 * entries on other servers are display-only with their server id.
 */
public class TownOutposts extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownOutposts ui = new TownOutposts();
        // cross-server outposts come from the database — prefetch async,
        // render on the main thread (TownUI thread discipline, §13.5)
        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
            try {
                ui.prefetched = DominionDOO.selectByTownId(TownManager.requireTown(townName).getId());
            } catch (Exception e) {
                Notification.error(sender, e);
                return;
            }
            cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                    () -> ui.displayByPreference(sender, townName, pageStr));
        });
    }

    private List<DominionDOO> prefetched;

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

    private static String worldLabel(DominionDOO dominion) {
        World world = Bukkit.getWorld(dominion.getWorldUid());
        return world != null ? world.getName() : "#" + dominion.getWorldUid().toString().substring(0, 8);
    }

    private static boolean isLocal(DominionDOO dominion) {
        return dominion.getServerId() == Configuration.multiServer.serverId;
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownOutpostsTuiText extends ConfigurationPart {
        public String title = "Outposts of {0}";
        public String button = "OUTPOSTS";
        public String outpostLine = "§b{0} §7- {1} ({2}, server {3})";
        public String remoteHint = "§8(on another server)";
        public String teleportButton = "TELEPORT";
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
                if (isLocal(outpost) && cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(player, outpost)) {
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
                        "", worldLabel(outpost), outpost.getClaimKind(), outpost.getServerId()));
                if (!isLocal(outpost)) {
                    line.append(TextUserInterface.townOutpostsTuiText.remoteHint);
                } else {
                    // same-server only: TeleportManager enforces the
                    // town-cascade TELEPORT flag, cooldown and delay
                    line.append(new FunctionalButton(TextUserInterface.townOutpostsTuiText.teleportButton) {
                        @Override
                        public void function() {
                            cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion(player, outpost);
                        }
                    }.needPermission(defaultPermission).build());
                }
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
                        "",
                        "§a▶ Left Click: manage (this server)",
                        "§b▶ Right Click: teleport (this server)"
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
                ChestButton btn = new ChestButton(ChestUserInterface.townOutpostsCui.outpostItemButton) {
                    @Override
                    public void onClick(ClickType type) {
                        if (type.isLeftClick() && local
                                && cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(player, outpost)) {
                            DominionManage.show(player, outpost.getName(), "1");
                        } else if (type.isRightClick() && local) {
                            // same-server only; TeleportManager enforces the
                            // town-cascade TELEPORT flag, cooldown and delay
                            cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion(player, outpost);
                        }
                    }
                };
                btn = btn.setDisplayNameArgs(outpost.getName());
                btn = btn.setLoreArgs(worldLabel(outpost), outpost.getClaimKind(),
                        String.valueOf(outpost.getServerId()));
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
                    outpost.getName(), worldLabel(outpost), outpost.getClaimKind(), outpost.getServerId()));
        }
    }
}
