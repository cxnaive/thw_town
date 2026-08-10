package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
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
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Town-level trust page (plan §13.3): manages TOWN-scoped grants and
 * lists the town's outposts as jump entries into {@link OutpostTrusts}.
 * Town-scope edits need mayor/assistant; per-outpost management lives
 * on the outpost page and follows isOutpostManager (contributor
 * included).
 */
public class TownTrusts extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownTrusts ui = new TownTrusts();
        // cross-server outposts come from the database — prefetch async,
        // render on the main thread (same discipline as TownOutposts)
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
        return (ListViewButton) new ListViewButton(TextUserInterface.townTrustsTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownTrustsTuiText extends ConfigurationPart {
        public String title = "Trusts of {0}";
        public String button = "TRUSTS";
        public String townScopeLabel = "§7[town]";
        public String outpostScopeLabel = "§7[outpost: {0}]";
        public String trustLine = "§b{0} {1}";
        public String addButton = "ADD TRUST";
        public String addHint = "Enter the player name to trust (town scope, all flags off — toggle after).";
        public String removeButton = "X";
        public String flagOn = "§a{0}";
        public String flagOff = "§8{0}";
        public String outpostsHeader = "§6--- Outpost trusts: pick an outpost ---";
        public String outpostLine = "§e{0} §7→ manage";
    }

    private static final String[] FLAG_NAMES = {"build", "destroy", "switch", "item_use"};

    private static String scopeLabel(TownTrustDOO trust) {
        if (trust.getScopeType() == TownTrustDOO.TrustScope.TOWN) {
            return TextUserInterface.townTrustsTuiText.townScopeLabel;
        }
        DominionDTO dto = CacheManager.instance.getDominion(trust.getScopeId());
        return formatString(TextUserInterface.townTrustsTuiText.outpostScopeLabel,
                dto == null ? "#" + trust.getScopeId() : dto.getName());
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);
            boolean manager = TownManager.isManager(town, player.getUniqueId())
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townTrustsTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townTrustsTuiText.button));

            if (manager) {
                view.add(Line.create().append(new FunctionalButton(TextUserInterface.townTrustsTuiText.addButton) {
                    @Override
                    public void function() {
                        createAddOn(player, town.getName());
                    }
                }.needPermission(defaultPermission).build()));
            }
            // town-scope rows only — outpost trusts live on OutpostTrusts
            for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
                if (trust.getScopeType() != TownTrustDOO.TrustScope.TOWN) continue;
                Line line = Line.create().append(formatString(TextUserInterface.townTrustsTuiText.trustLine,
                        CacheManager.instance.getPlayerName(trust.getPlayerUuid()),
                        TextUserInterface.townTrustsTuiText.townScopeLabel));
                if (manager) {
                    boolean[] values = {trust.isBuild(), trust.isDestroy(), trust.isSwitch(), trust.isItemUse()};
                    for (int i = 0; i < FLAG_NAMES.length; i++) {
                        final int idx = i;
                        line.append(new FunctionalButton(formatString(
                                values[i] ? TextUserInterface.townTrustsTuiText.flagOn
                                        : TextUserInterface.townTrustsTuiText.flagOff, FLAG_NAMES[i])) {
                            @Override
                            public void function() {
                                TownUI.action(player, () -> {
                                    boolean[] next = {trust.isBuild(), trust.isDestroy(), trust.isSwitch(), trust.isItemUse()};
                                    next[idx] = !next[idx];
                                    TownManager.trust(player, town, trust.getPlayerUuid(),
                                            trust.getScopeType(), null,
                                            next[0], next[1], next[2], next[3]);
                                }, () -> show(player, town.getName(), "1"));
                            }
                        }.needPermission(defaultPermission).build());
                    }
                    line.append(new FunctionalButton(TextUserInterface.townTrustsTuiText.removeButton) {
                        @Override
                        public void function() {
                            TownUI.action(player,
                                    () -> TownManager.untrust(player, town, trust.getPlayerUuid(),
                                            trust.getScopeType(), null),
                                    () -> show(player, town.getName(), "1"));
                        }
                    }.needPermission(defaultPermission).build());
                }
                view.add(line);
            }
            // outpost jump entries — visible to everyone; the edit gate is
            // per-outpost (isOutpostManager) on the target page
            List<DominionDOO> outposts = outposts(town.getId());
            if (!outposts.isEmpty()) {
                view.add(Line.create().append(TextUserInterface.townTrustsTuiText.outpostsHeader));
                for (DominionDOO outpost : outposts) {
                    view.add(Line.create().append(new FunctionalButton(formatString(
                            TextUserInterface.townTrustsTuiText.outpostLine, outpost.getName())) {
                        @Override
                        public void function() {
                            OutpostTrusts.show(player, outpost.getName(), "1");
                        }
                    }.needPermission(defaultPermission).build()));
                }
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    private static void createAddOn(Player player, String townName) {
        new InputterRunner(player, TextUserInterface.townTrustsTuiText.addHint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> runAsync(input));
            }

            private void runAsync(String input) {
                try {
                    TownDOO town = TownManager.requireTown(townName);
                    cn.lunadeer.dominion.api.dtos.PlayerDTO target =
                            CacheManager.instance.getPlayer(input.trim());
                    if (target == null) {
                        Notification.error(player, cn.lunadeer.dominion.configuration.Language
                                .townCommandText.playerNotFound, input);
                        return;
                    }
                    TownManager.trust(player, town, target.getUuid(),
                            TownTrustDOO.TrustScope.TOWN, null, false, false, false, false);
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                            () -> show(player, townName, "1"));
                } catch (Exception e) {
                    Notification.error(player, e);
                }
            }
        };
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownTrustsCui extends ConfigurationPart {
        public String title = "§6✦ §eTrusts of {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<########",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "#iiiiiii#",
                        "p######An"
                )
        );
        public ButtonConfiguration trustItemButton = ButtonConfiguration.createMaterial(
                'i', Material.PLAYER_HEAD, "§e{0}",
                List.of(
                        "§7Scope: §bAll town outposts",
                        "§7Flags: §b{0}",
                        "§8B Build · D Destroy · S Switch · I Item use",
                        "",
                        "§a▶ Click to manage four independent flags"
                )
        );
        public ButtonConfiguration addItemButton = ButtonConfiguration.createMaterial(
                'A', Material.WRITABLE_BOOK, "§aAdd trust",
                List.of("§7Trust a player (town scope).", "§a▶ Click to add"));
        public ButtonConfiguration outpostItemButton = ButtonConfiguration.createMaterial(
                'i', Material.GRASS_BLOCK, "§e{0}",
                List.of("§7Manage this outpost's trusts.", "§a▶ Click to open"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            boolean manager = TownManager.isManager(town, player.getUniqueId())
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            String currentPage = String.valueOf(toIntegrity(args[1], 1));

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townTrustsCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townTrustsCui.listConfiguration,
                    Integer.parseInt(currentPage));

            for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
                if (trust.getScopeType() != TownTrustDOO.TrustScope.TOWN) continue;
                String flags = (trust.isBuild() ? "B" : "-") + (trust.isDestroy() ? "D" : "-")
                        + (trust.isSwitch() ? "S" : "-") + (trust.isItemUse() ? "I" : "-");
                String playerName = CacheManager.instance.getPlayerName(trust.getPlayerUuid());
                ButtonConfiguration item = ButtonConfiguration.createHeadByName(
                        ChestUserInterface.townTrustsCui.listConfiguration.itemSymbol.charAt(0),
                        playerName,
                        ChestUserInterface.townTrustsCui.trustItemButton.name,
                        ChestUserInterface.townTrustsCui.trustItemButton.lore
                );
                ChestButton btn = new ChestButton(item) {
                    @Override
                    public void onClick(ClickType type) {
                        TownTrustEditor.showTown(player, town, trust.getPlayerUuid(), currentPage);
                    }
                };
                btn = btn.setDisplayNameArgs(playerName);
                btn = btn.setLoreArgs(flags);
                view = view.addItem(btn);
            }
            // outpost jump entries — open the per-outpost trust page
            for (DominionDOO outpost : outposts(town.getId())) {
                ChestButton jump = new ChestButton(ChestUserInterface.townTrustsCui.outpostItemButton) {
                    @Override
                    public void onClick(ClickType type) {
                        OutpostTrusts.show(player, outpost.getName(), "1");
                    }
                };
                jump = jump.setDisplayNameArgs(outpost.getName());
                view = view.addItem(jump);
            }
            final ChestListView viewFinal = view;
            if (manager) {
                view.setButton(ChestUserInterface.townTrustsCui.addItemButton.getSymbol(),
                        new ChestButton(ChestUserInterface.townTrustsCui.addItemButton) {
                            @Override
                            public void onClick(ClickType type) {
                                viewFinal.close();
                                createAddOn(player, town.getName());
                            }
                        });
            }
            view.setButton(ChestUserInterface.townTrustsCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townTrustsCui.backButton) {
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
        for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
            Notification.info(sender, formatString(TextUserInterface.townTrustsTuiText.trustLine,
                    CacheManager.instance.getPlayerName(trust.getPlayerUuid()), scopeLabel(trust)));
        }
    }
}
