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
 * Town trust grants (plan §13.3): town-scoped entries first, then
 * per-outpost. Each flag is a toggle (trust() is an upsert); town scope
 * edits need mayor/assistant, outpost scope additionally allows that
 * outpost's appointed admin — same rule as the command layer.
 */
public class TownTrusts extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        new TownTrusts().displayByPreference(sender, townName, pageStr);
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
    }

    private static final String[] FLAG_NAMES = {"build", "destroy", "switch", "item_use"};

    private static boolean canEdit(Player player, TownDOO town, TownTrustDOO trust) {
        if (cn.lunadeer.dominion.misc.Others.bypassLimit(player)) return true;
        if (TownManager.isManager(town, player.getUniqueId())) return true;
        if (trust.getScopeType() == TownTrustDOO.TrustScope.OUTPOST) {
            DominionDTO dto = CacheManager.instance.getDominion(trust.getScopeId());
            return dto instanceof DominionDOO doo
                    && player.getUniqueId().equals(doo.getOutpostAdminUuid());
        }
        return false;
    }

    /** Editable in the UI: authority plus, for outpost scope, local resolution. */
    private static boolean canEditInUi(Player player, TownDOO town, TownTrustDOO trust) {
        if (!canEdit(player, town, trust)) return false;
        // a remote outpost's grant cannot be resolved through this
        // server's dominion cache — render it read-only rather than
        // letting edits fail or silently no-op
        return trust.getScopeType() != TownTrustDOO.TrustScope.OUTPOST || outpostOf(trust) != null;
    }

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
            // copy before sorting — the cache list is shared mutable state
            List<TownTrustDOO> trusts = new java.util.ArrayList<>(CacheManager.instance.getTownCache().getTrustsOf(town.getId()));
            // town scope first, then outposts
            trusts.sort((a, b) -> {
                if (a.getScopeType() != b.getScopeType()) {
                    return a.getScopeType() == TownTrustDOO.TrustScope.TOWN ? -1 : 1;
                }
                return a.getScopeId().compareTo(b.getScopeId());
            });
            for (TownTrustDOO trust : trusts) {
                Line line = Line.create().append(formatString(TextUserInterface.townTrustsTuiText.trustLine,
                        CacheManager.instance.getPlayerName(trust.getPlayerUuid()), scopeLabel(trust)));
                if (canEditInUi(player, town, trust)) {
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
                                            trust.getScopeType(), outpostOf(trust),
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
                                            trust.getScopeType(), outpostOf(trust)),
                                    () -> show(player, town.getName(), "1"));
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

    private static DominionDOO outpostOf(TownTrustDOO trust) {
        if (trust.getScopeType() != TownTrustDOO.TrustScope.OUTPOST) return null;
        DominionDTO dto = CacheManager.instance.getDominion(trust.getScopeId());
        return dto instanceof DominionDOO doo ? doo : null;
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
                'i', Material.NAME_TAG, "§e{0}",
                List.of(
                        "§7Scope: §b{0}",
                        "§7build/destroy/switch/item_use: §b{1}",
                        "",
                        "§a▶ Left Click: cycle flags (editors)",
                        "§c▶ Right Click: remove (editors)"
                )
        );
        public ButtonConfiguration addItemButton = ButtonConfiguration.createMaterial(
                'A', Material.WRITABLE_BOOK, "§aAdd trust",
                List.of("§7Trust a player (town scope).", "§a▶ Click to add"));
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

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townTrustsCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townTrustsCui.listConfiguration, toIntegrity(args[1], 1));

            for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
                String scope = trust.getScopeType() == TownTrustDOO.TrustScope.TOWN
                        ? "town" : "outpost #" + trust.getScopeId();
                String flags = (trust.isBuild() ? "B" : "-") + (trust.isDestroy() ? "D" : "-")
                        + (trust.isSwitch() ? "S" : "-") + (trust.isItemUse() ? "I" : "-");
                ChestButton btn = new ChestButton(ChestUserInterface.townTrustsCui.trustItemButton) {
                    @Override
                    public void onClick(ClickType type) {
                        if (!canEditInUi(player, town, trust)) return;
                        TownUI.action(player, () -> {
                            if (type.isLeftClick()) {
                                // cycle: all-off -> build -> +destroy -> +switch -> +item_use -> all-off
                                boolean[] next;
                                if (!trust.isBuild()) next = new boolean[]{true, false, false, false};
                                else if (!trust.isDestroy()) next = new boolean[]{true, true, false, false};
                                else if (!trust.isSwitch()) next = new boolean[]{true, true, true, false};
                                else if (!trust.isItemUse()) next = new boolean[]{true, true, true, true};
                                else next = new boolean[]{false, false, false, false};
                                TownManager.trust(player, town, trust.getPlayerUuid(),
                                        trust.getScopeType(), outpostOf(trust),
                                        next[0], next[1], next[2], next[3]);
                            } else if (type.isRightClick()) {
                                TownManager.untrust(player, town, trust.getPlayerUuid(),
                                        trust.getScopeType(), outpostOf(trust));
                            }
                        }, () -> show(player, town.getName(), "1"));
                    }
                };
                btn = btn.setDisplayNameArgs(CacheManager.instance.getPlayerName(trust.getPlayerUuid()));
                btn = btn.setLoreArgs(scope, flags);
                view = view.addItem(btn);
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
