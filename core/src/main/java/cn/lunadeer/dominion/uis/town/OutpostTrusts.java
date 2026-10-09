package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.misc.DisplayNames;
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
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Trust grants of ONE town outpost. Reached from the town-level
 * {@link TownTrusts} page by picking an outpost. Anyone with outpost
 * management authority (mayor/assistant, the contributor, the appointed
 * outpost admin — {@link TownManager#isOutpostManager}) may edit, and
 * sees only this outpost's rows; town-scoped rows live on the town page.
 */
public class OutpostTrusts extends AbstractUI {

    public static void show(CommandSender sender, String dominionName, String pageStr) {
        new OutpostTrusts().displayByPreference(sender, dominionName, pageStr);
    }

    private static DominionDOO requireOutpost(String dominionName) throws Exception {
        DominionDTO dto = CacheManager.instance.getDominion(dominionName);
        if (!(dto instanceof DominionDOO doo) || !doo.isTownOutpost()) {
            throw new cn.lunadeer.dominion.misc.DominionException(
                    cn.lunadeer.dominion.configuration.Language.townCommandText.dominionNotExist, dominionName);
        }
        return doo;
    }

    private static boolean canEdit(Player player, DominionDOO outpost) {
        return cn.lunadeer.dominion.misc.Others.bypassLimit(player)
                || TownManager.isOutpostManager(player, outpost);
    }

    private static List<TownTrustDOO> outpostTrusts(TownDOO town, DominionDOO outpost) {
        List<TownTrustDOO> rows = new ArrayList<>();
        for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
            if (trust.getScopeType() == TownTrustDOO.TrustScope.OUTPOST
                    && trust.getScopeId().equals(outpost.getId())) {
                rows.add(trust);
            }
        }
        return rows;
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class OutpostTrustsTuiText extends ConfigurationPart {
        public String title = "Trusts of outpost {0}";
        public String trustLine = "§b{0}";
        public String addButton = "ADD TRUST";
        public String addHint = "Enter the player name to trust on this outpost (all flags off — toggle after).";
        public String removeButton = "X";
        public String flagOn = "§a{0}: additional access";
        public String flagOff = "§8{0}: no additional access";
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            DominionDOO outpost = requireOutpost(args[0]);
            TownDOO town = TownManager.requireTown(outpost.getTownId());
            int page = toIntegrity(args[1], 1);
            boolean editor = canEdit(player, outpost);

            ListView view = ListView.create(10, TownTrusts.button(player, town.getName()));
            view.title(formatString(TextUserInterface.outpostTrustsTuiText.title, outpost.getName()));
            view.navigator(Line.create()
                    .append(TownTrusts.button(player, town.getName()).build())
                    .append(outpost.getName())
                    .append(TownGuide.button(player, TownGuide.Topic.TRUST, town.getId(),
                            () -> show(player, outpost.getName(), args[1])).build()));

            if (editor) {
                view.add(Line.create().append(new FunctionalButton(TextUserInterface.outpostTrustsTuiText.addButton) {
                    @Override
                    public void function() {
                        createAddOn(player, outpost.getName());
                    }
                }.needPermission(defaultPermission).build()));
            }
            for (TownTrustDOO trust : outpostTrusts(town, outpost)) {
                Line line = Line.create().append(formatString(TextUserInterface.outpostTrustsTuiText.trustLine,
                        CacheManager.instance.getPlayerName(trust.getPlayerUuid())));
                if (editor) {
                    boolean[] values = {trust.isBuild(), trust.isDestroy(), trust.isSwitch(), trust.isItemUse()};
                    List<String> labels = DisplayNames.trustCategories();
                    for (int i = 0; i < labels.size(); i++) {
                        final int idx = i;
                        line.append(new FunctionalButton(formatString(
                                values[i] ? TextUserInterface.outpostTrustsTuiText.flagOn
                                        : TextUserInterface.outpostTrustsTuiText.flagOff, labels.get(i))) {
                            @Override
                            public void function() {
                                TownUI.action(player, () -> {
                                    boolean[] next = {trust.isBuild(), trust.isDestroy(), trust.isSwitch(), trust.isItemUse()};
                                    next[idx] = !next[idx];
                                    TownManager.trust(player, town, trust.getPlayerUuid(),
                                            TownTrustDOO.TrustScope.OUTPOST, outpost,
                                            next[0], next[1], next[2], next[3]);
                                }, () -> show(player, outpost.getName(), "1"));
                            }
                        }.needPermission(defaultPermission).build());
                    }
                    line.append(new FunctionalButton(TextUserInterface.outpostTrustsTuiText.removeButton) {
                        @Override
                        public void function() {
                            TownUI.action(player,
                                    () -> TownManager.untrust(player, town, trust.getPlayerUuid(),
                                            TownTrustDOO.TrustScope.OUTPOST, outpost),
                                    () -> show(player, outpost.getName(), "1"));
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

    private static void createAddOn(Player player, String dominionName) {
        new InputterRunner(player, TextUserInterface.outpostTrustsTuiText.addHint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> runAsync(input));
            }

            private void runAsync(String input) {
                try {
                    DominionDOO outpost = requireOutpost(dominionName);
                    TownDOO town = TownManager.requireTown(outpost.getTownId());
                    cn.lunadeer.dominion.api.dtos.PlayerDTO target =
                            CacheManager.instance.getPlayer(input.trim());
                    if (target == null) {
                        Notification.error(player, cn.lunadeer.dominion.configuration.Language
                                .townCommandText.playerNotFound, input);
                        return;
                    }
                    TownManager.trust(player, town, target.getUuid(),
                            TownTrustDOO.TrustScope.OUTPOST, outpost, false, false, false, false);
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                            () -> show(player, dominionName, "1"));
                } catch (Exception e) {
                    Notification.error(player, e);
                }
            }
        };
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class OutpostTrustsCui extends ConfigurationPart {
        public String title = "§6✦ §eTrusts: {0} §6✦";
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
                        "§7Scope: §bThis outpost only",
                        "§7Additional access: §b{0}",
                        "§8Other categories keep their existing permissions.",
                        "",
                        "§a▶ Click to manage four independent flags"
                )
        );
        public ButtonConfiguration addItemButton = ButtonConfiguration.createMaterial(
                'A', Material.WRITABLE_BOOK, "§aAdd trust",
                List.of("§7Trust a player (this outpost only).", "§a▶ Click to add"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            DominionDOO outpost = requireOutpost(args[0]);
            TownDOO town = TownManager.requireTown(outpost.getTownId());
            boolean editor = canEdit(player, outpost);
            String currentPage = String.valueOf(toIntegrity(args[1], 1));

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.outpostTrustsCui.title, outpost.getName()));
            view.applyListConfiguration(ChestUserInterface.outpostTrustsCui.listConfiguration,
                    Integer.parseInt(currentPage));

            for (TownTrustDOO trust : outpostTrusts(town, outpost)) {
                String playerName = CacheManager.instance.getPlayerName(trust.getPlayerUuid());
                ButtonConfiguration item = ButtonConfiguration.createHeadByName(
                        ChestUserInterface.outpostTrustsCui.listConfiguration.itemSymbol.charAt(0),
                        playerName,
                        ChestUserInterface.outpostTrustsCui.trustItemButton.name,
                        ChestUserInterface.outpostTrustsCui.trustItemButton.lore
                );
                ChestButton btn = new ChestButton(item) {
                    @Override
                    public void onClick(ClickType type) {
                        TownTrustEditor.showOutpost(player, outpost, trust.getPlayerUuid(), currentPage);
                    }
                };
                btn = btn.setDisplayNameArgs(playerName);
                btn = btn.setLoreArgs(DisplayNames.additionalTrust(trust));
                view = view.addItem(btn);
            }
            final ChestListView viewFinal = view;
            if (editor) {
                view.setButton(ChestUserInterface.outpostTrustsCui.addItemButton.getSymbol(),
                        new ChestButton(ChestUserInterface.outpostTrustsCui.addItemButton) {
                            @Override
                            public void onClick(ClickType type) {
                                viewFinal.close();
                                createAddOn(player, outpost.getName());
                            }
                        });
            }
            view.setButton(ChestUserInterface.outpostTrustsCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.outpostTrustsCui.backButton) {
                        @Override
                        public void onClick(ClickType type) {
                            TownTrusts.show(player, town.getName(), "1");
                        }
                    });
            TownGuide.addHelpButton(view, player, TownGuide.Topic.TRUST, town.getId(),
                    () -> show(player, outpost.getName(), args[1]));
            view.open();
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        DominionDOO outpost = requireOutpost(args[0]);
        TownDOO town = TownManager.requireTown(outpost.getTownId());
        for (TownTrustDOO trust : outpostTrusts(town, outpost)) {
            Notification.info(sender, formatString(TextUserInterface.outpostTrustsTuiText.trustLine,
                    CacheManager.instance.getPlayerName(trust.getPlayerUuid())));
        }
    }
}
