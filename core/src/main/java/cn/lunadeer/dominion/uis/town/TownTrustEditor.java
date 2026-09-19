package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.PendingConfirmation;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.UUID;

import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * CUI editor for one TownTrust row. A player head in the trust list opens
 * this page so each of the four trust categories has an explicit toggle.
 */
public final class TownTrustEditor {

    private TownTrustEditor() {
    }

    public static class TownTrustEditorCui extends ConfigurationPart {
        public String townTitle = "§6✦ §eTrust permissions: {0} §6✦";
        public String outpostTitle = "§6✦ §eOutpost trust permissions: {0} §6✦";
        public List<String> layout = List.of(
                "<###P###R",
                "#########",
                "##BDSI###"
        );

        public ButtonConfiguration targetButton = ButtonConfiguration.createMaterial(
                'P', Material.PLAYER_HEAD, "§e{0}",
                List.of("§7Configure this player's four trust categories.")
        );
        public ButtonConfiguration buildButton = ButtonConfiguration.createMaterial(
                'B', Material.BRICKS, "§eBuild (B)", List.of(
                        "§7State: {0}", "§7{1}", "", "{2}"
                )
        );
        public ButtonConfiguration destroyButton = ButtonConfiguration.createMaterial(
                'D', Material.DIAMOND_PICKAXE, "§eDestroy (D)", List.of(
                        "§7State: {0}", "§7{1}", "", "{2}"
                )
        );
        public ButtonConfiguration switchButton = ButtonConfiguration.createMaterial(
                'S', Material.LEVER, "§eSwitch (S)", List.of(
                        "§7State: {0}", "§7{1}", "", "{2}"
                )
        );
        public ButtonConfiguration itemUseButton = ButtonConfiguration.createMaterial(
                'I', Material.CRAFTING_TABLE, "§eItem use (I)", List.of(
                        "§7State: {0}", "§7{1}", "", "{2}"
                )
        );
        public String enabledState = "§aEnabled";
        public String disabledState = "§cDisabled";
        public String toggleHint = "§a▶ Click to toggle";
        public String readOnlyHint = "§8Read-only: you cannot edit this trust";
        public String buildDescription = "Place, sow, and spawn vehicles.";
        public String destroyDescription = "Break, harvest, and destroy vehicles.";
        public String switchDescription = "Use doors, buttons, containers, and other switches.";
        public String itemUseDescription = "Craft, trade, use items, and interact with entities.";
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to return to the trust list")
        );
        public ButtonConfiguration removeButton = ButtonConfiguration.createMaterial(
                'R', Material.BARRIER, "§cRemove trust",
                List.of("§7Remove all trust categories for this player.", "§c▶ Click twice to confirm")
        );
        public String removeConfirm = "§cClick Remove trust again within 5 seconds to remove {0}.";
    }

    private record Context(TownDOO town, DominionDOO outpost, UUID target, TownTrustDOO trust,
                           boolean editor, String returnPage) {
        private boolean isOutpost() {
            return outpost != null;
        }
    }

    public static void showTown(Player player, TownDOO town, UUID target, String returnPage) {
        try {
            TownTrustDOO trust = find(town, target, TownTrustDOO.TrustScope.TOWN, town.getId());
            if (trust == null) throw new IllegalStateException("Trust entry no longer exists.");
            boolean editor = TownManager.isManager(town, player.getUniqueId())
                    || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            render(player, new Context(town, null, target, trust, editor, returnPage));
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    public static void showOutpost(Player player, DominionDOO outpost, UUID target, String returnPage) {
        try {
            TownDOO town = TownManager.requireTown(outpost.getTownId());
            TownTrustDOO trust = find(town, target, TownTrustDOO.TrustScope.OUTPOST, outpost.getId());
            if (trust == null) throw new IllegalStateException("Trust entry no longer exists.");
            boolean editor = cn.lunadeer.dominion.misc.Others.bypassLimit(player)
                    || TownManager.isOutpostManager(player, outpost);
            render(player, new Context(town, outpost, target, trust, editor, returnPage));
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    private static TownTrustDOO find(TownDOO town, UUID target, TownTrustDOO.TrustScope scope, Integer scopeId) {
        for (TownTrustDOO trust : CacheManager.instance.getTownCache().getTrustsOf(town.getId())) {
            if (trust.getPlayerUuid().equals(target)
                    && trust.getScopeType() == scope
                    && trust.getScopeId().equals(scopeId)) return trust;
        }
        return null;
    }

    private static void render(Player player, Context context) {
        TownTrustEditorCui config = ChestUserInterface.townTrustEditorCui;
        String playerName = CacheManager.instance.getPlayerName(context.target());
        ChestView view = ChestUserInterfaceManager.getInstance().getViewOf(player);
        view.setLayout(config.layout);
        view.setTitle(formatString(context.isOutpost() ? config.outpostTitle : config.townTitle, playerName));

        ButtonConfiguration targetConfig = ButtonConfiguration.createHeadByName(
                config.targetButton.getSymbol(), playerName, config.targetButton.name, config.targetButton.lore);
        view.setButton(targetConfig.getSymbol(), new ChestButton(targetConfig) {
            @Override
            public void onClick(ClickType type) {
                // The target head is informational; flags have their own buttons.
            }
        }.setDisplayNameArgs(playerName));

        view.setButton(config.buildButton.getSymbol(), flagButton(player, context,
                config.buildButton, context.trust().isBuild(), config.buildDescription,
                TownManager.TrustCategory.BUILD));
        view.setButton(config.destroyButton.getSymbol(), flagButton(player, context,
                config.destroyButton, context.trust().isDestroy(), config.destroyDescription,
                TownManager.TrustCategory.DESTROY));
        view.setButton(config.switchButton.getSymbol(), flagButton(player, context,
                config.switchButton, context.trust().isSwitch(), config.switchDescription,
                TownManager.TrustCategory.SWITCH));
        view.setButton(config.itemUseButton.getSymbol(), flagButton(player, context,
                config.itemUseButton, context.trust().isItemUse(), config.itemUseDescription,
                TownManager.TrustCategory.ITEM_USE));

        view.setButton(config.backButton.getSymbol(), new ChestButton(config.backButton) {
            @Override
            public void onClick(ClickType type) {
                returnToList(player, context);
            }
        });
        if (context.editor()) {
            view.setButton(config.removeButton.getSymbol(), new ChestButton(config.removeButton) {
                @Override
                public void onClick(ClickType type) {
                    PendingConfirmation.requestConfirm(
                            player,
                            "town-trust-remove:" + context.town().getId() + ":" + context.target()
                                    + ":" + context.trust().getScopeType() + ":" + context.trust().getScopeId(),
                            () -> TownUI.action(player,
                                    () -> TownManager.untrust(player, context.town(), context.target(),
                                            context.trust().getScopeType(), context.outpost()),
                                    () -> returnToList(player, context)),
                            formatString(config.removeConfirm, playerName)
                    );
                }
            });
        }
        TownGuide.addHelpButton(view, player, TownGuide.Topic.TRUST, context.town().getId(), () -> {
            if (context.isOutpost()) showOutpost(player, context.outpost(), context.target(), context.returnPage());
            else showTown(player, context.town(), context.target(), context.returnPage());
        });
        view.open();
    }

    private static ChestButton flagButton(Player player, Context context, ButtonConfiguration config,
                                          boolean value, String description,
                                          TownManager.TrustCategory category) {
        TownTrustEditorCui settings = ChestUserInterface.townTrustEditorCui;
        ChestButton button = new ChestButton(config) {
            @Override
            public void onClick(ClickType type) {
                if (!context.editor()) return;
                boolean[] next = {
                        context.trust().isBuild(), context.trust().isDestroy(),
                        context.trust().isSwitch(), context.trust().isItemUse()
                };
                switch (category) {
                    case BUILD -> next[0] = !next[0];
                    case DESTROY -> next[1] = !next[1];
                    case SWITCH -> next[2] = !next[2];
                    case ITEM_USE -> next[3] = !next[3];
                }
                TownUI.action(player,
                        () -> TownManager.trust(player, context.town(), context.target(),
                                context.trust().getScopeType(), context.outpost(),
                                next[0], next[1], next[2], next[3]),
                        () -> {
                            if (context.isOutpost()) {
                                showOutpost(player, context.outpost(), context.target(), context.returnPage());
                            } else {
                                showTown(player, context.town(), context.target(), context.returnPage());
                            }
                        });
            }
        };
        return button.setLoreArgs(value ? settings.enabledState : settings.disabledState, description,
                context.editor() ? settings.toggleHint : settings.readOnlyHint);
    }

    private static void returnToList(Player player, Context context) {
        if (context.isOutpost()) {
            OutpostTrusts.show(player, context.outpost().getName(), context.returnPage());
        } else {
            TownTrusts.show(player, context.town().getName(), context.returnPage());
        }
    }
}
