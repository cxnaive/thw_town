package cn.lunadeer.dominion.uis.dominion;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.DominionNode;
import cn.lunadeer.dominion.commands.DominionOperateCommand;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.misc.CommandArguments;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.AllDominion;
import cn.lunadeer.dominion.uis.MainMenu;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.command.SecondaryCommand;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestListView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.scui.configuration.ListViewConfiguration;
import cn.lunadeer.dominion.utils.stui.ListView;
import cn.lunadeer.dominion.utils.stui.ViewStyles;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.managers.TeleportManager.teleportToDominion;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

public class DominionList extends AbstractUI {

    public static void show(CommandSender sender, String pageStr) {
        DominionList ui = new DominionList();
        if (!(sender instanceof Player player)) {
            ui.displayByPreference(sender, pageStr);
            return;
        }
        // the managed-town section queries the DB (cross-server) — prefetch
        // async, render sync (TownUI thread discipline, §13.5)
        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
            try {
                ui.prefetchedManaged = managedTownOutposts(player);
            } catch (Exception e) {
                Notification.error(player, e);
                return;
            }
            cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                    () -> ui.displayByPreference(sender, pageStr));
        });
    }

    private List<cn.lunadeer.dominion.doos.DominionDOO> prefetchedManaged;

    private List<cn.lunadeer.dominion.doos.DominionDOO> managedOutposts(Player player) throws Exception {
        return prefetchedManaged != null ? prefetchedManaged : managedTownOutposts(player);
    }

    public static SecondaryCommand list = new SecondaryCommand("list", List.of(
            new CommandArguments.OptionalPageArgument()
    ), Language.uiCommandsDescription.dominionList) {
        @Override
        public void executeHandler(CommandSender sender) {
            try {
                show(sender, getArgumentValue(0));
            } catch (Exception e) {
                Notification.error(sender, e.getMessage());
            }
        }
    }.needPermission(defaultPermission).register();

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class DominionListTuiText extends ConfigurationPart {
        public String title = "Your Dominions";
        public String button = "DOMINIONS";
        public String description = "List all of your dominions.";
        public String deleteButton = "DELETE";
        public String adminSection = "Your admin dominions section.";
        public String serverSection = "Server {0} dominions section.";
        public String townTag = " [{0}]";
        public String townManagedSection = "Town outposts you manage (all servers).";
        public String townManagedLine = "§b{0} §7- town: {1}, server {2}";
        public String remoteOwnedSection = "Your dominions on other servers.";
        public String remoteOwnedLine = "§b{0} §7- server {1}";
        public String legacyTag = " (legacy - create or join a town!)";
        public String legacyNotice = "Personal dominions are a legacy system - create or join a town soon (/town create, /town join, or the main menu); your land will attach as town outposts.";
    }

    public static ListViewButton button(CommandSender sender) {
        return (ListViewButton) new ListViewButton(TextUserInterface.dominionListTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    public static List<Line> BuildTreeLines(CommandSender sender, List<DominionNode> dominionTree, Integer depth) {
        List<Line> lines = new ArrayList<>();
        StringBuilder prefix = new StringBuilder();
        prefix.append(" | ".repeat(Math.max(0, depth)));
        for (DominionNode node : dominionTree) {
            Line line = Line.create();
            // town era: town outposts cannot be deleted or personally
            // managed — the contributor gets a read-only info entry, town
            // managers keep MANAGE, DELETE never renders (§13.4)
            boolean townOutpost = node.getDominion() instanceof cn.lunadeer.dominion.doos.DominionDOO doo
                    && doo.isTownOutpost();
            if (!townOutpost) {
                TextComponent delete = new FunctionalButton(TextUserInterface.dominionListTuiText.deleteButton) {
                    @Override
                    public void function() {
                        DominionOperateCommand.delete(sender, node.getDominion().getName(), "");
                    }
                }.red().build();
                line.append(delete);
            }
            boolean canManage = !(sender instanceof Player p)
                    || cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(p, node.getDominion());
            if (canManage) {
                line.append(DominionManage.button(sender, node.getDominion().getName()).green().build());
            } else {
                // reachable only for players (console always canManage)
                line.append(cn.lunadeer.dominion.uis.dominion.manage.Info.button((Player) sender,
                        node.getDominion().getName()).build());
            }
            TextComponent tp = new FunctionalButton("TP") {
                @Override
                public void function() {
                    if (sender instanceof Player player)
                        teleportToDominion(player, node.getDominion());
                }
            }.build();
            line.append(tp).append(prefix + node.getDominion().getName());
            if (townOutpost) {
                cn.lunadeer.dominion.doos.TownDOO town = CacheManager.instance.getTownCache()
                        .getTown(((cn.lunadeer.dominion.doos.DominionDOO) node.getDominion()).getTownId());
                line.append(Component.text(formatString(TextUserInterface.dominionListTuiText.townTag,
                        town == null ? "?" : town.getName()), ViewStyles.SECONDARY));
            }
            lines.add(line);
            lines.addAll(BuildTreeLines(sender, node.getChildren(), depth + 1));
        }
        return lines;
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        int page = toIntegrity(args[0], 1);
        ListView view = ListView.create(10, button(player));

        view.title(TextUserInterface.dominionListTuiText.title);
        view.navigator(Line.create()
                .append(MainMenu.button(player).build())
                .append(TextUserInterface.dominionListTuiText.button));
        List<DominionNode> dominionNodes = CacheManager.instance.getCache().getDominionCache().getPlayerDominionNodes(player.getUniqueId());
        // town era: nag owners of personal (non-town) land to migrate —
        // personal dominions are frozen legacy
        if (hasLegacyDominion(player)) {
            view.add(Line.create().append(Component.text(
                    TextUserInterface.dominionListTuiText.legacyNotice, ViewStyles.SEVERE)));
        }
        // Show dominions on current server
        view.addLines(BuildTreeLines(player, dominionNodes, 0));
        // Show admin dominions on this server
        List<DominionDTO> admin_dominions = CacheManager.instance.getCache().getDominionCache().getPlayerAdminDominionDTOs(player.getUniqueId());
        if (!admin_dominions.isEmpty()) {
            view.add(Line.create().append(""));
            view.add(Line.create().append(Component.text(TextUserInterface.dominionListTuiText.adminSection, ViewStyles.PRIMARY)));
            for (DominionDTO dominion : admin_dominions) {
                TextComponent manage = DominionManage.button(player, dominion.getName()).build();
                view.add(Line.create().append(manage).append(dominion.getName()));
            }
        }
        // Town era (§13.4): outposts I manage as mayor/assistant or as an
        // appointed outpost admin — cross-server, since towns are global.
        // Rows already shown in the owned tree above are skipped.
        view.addLines(buildManagedTownLines(player));
        // Multi-server: dominions I own on OTHER backend servers never
        // appear in the local tree — list them flat, info-only (manage
        // and teleport are same-server operations).
        view.addLines(buildRemoteOwnedLines(player));
        view.showOn(player, page);
    }

    /** True while the player still owns any personal (non-town) dominion. */
    private static boolean hasLegacyDominion(Player player) {
        List<DominionDTO> own = Configuration.multiServer.enable
                ? CacheManager.instance.getPlayerOwnDominionDTOs(player.getUniqueId())
                : CacheManager.instance.getCache().getDominionCache().getPlayerOwnDominionDTOs(player.getUniqueId());
        for (DominionDTO dominion : own) {
            if (!(dominion instanceof cn.lunadeer.dominion.doos.DominionDOO doo) || !doo.isTownOutpost()) {
                return true;
            }
        }
        return false;
    }

    /** Owned dominions living on other backend servers (aggregate view). */
    private List<Line> buildRemoteOwnedLines(Player player) {
        List<Line> lines = new ArrayList<>();
        if (!Configuration.multiServer.enable) {
            return lines;
        }
        int thisServer = Configuration.multiServer.serverId;
        for (DominionDTO dominion : CacheManager.instance.getPlayerOwnDominionDTOs(player.getUniqueId())) {
            if (dominion.getServerId() == thisServer || dominion.getId() == -1) continue;
            Line line = Line.create();
            line.append(cn.lunadeer.dominion.uis.dominion.manage.Info.button(player, dominion.getName()).build());
            String townTag = "";
            if (dominion instanceof cn.lunadeer.dominion.doos.DominionDOO doo && doo.isTownOutpost()) {
                cn.lunadeer.dominion.doos.TownDOO town = CacheManager.instance.getTownCache()
                        .getTown(doo.getTownId());
                townTag = formatString(TextUserInterface.dominionListTuiText.townTag,
                        town == null ? "?" : town.getName());
            }
            line.append(Component.text(formatString(TextUserInterface.dominionListTuiText.remoteOwnedLine,
                    dominion.getName(), dominion.getServerId()) + townTag, ViewStyles.SECONDARY));
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return lines;
        }
        List<Line> result = new ArrayList<>();
        result.add(Line.create().append(""));
        result.add(Line.create().append(Component.text(
                TextUserInterface.dominionListTuiText.remoteOwnedSection, ViewStyles.PRIMARY)));
        result.addAll(lines);
        return result;
    }

    /**
     * Outposts the player manages as mayor/assistant or as an appointed
     * outpost admin — cross-server (DB-backed), deduplicated, order kept.
     */
    public static List<cn.lunadeer.dominion.doos.DominionDOO> managedTownOutposts(Player player) throws Exception {
        List<cn.lunadeer.dominion.doos.DominionDOO> managed = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (cn.lunadeer.dominion.doos.TownMemberDOO membership : CacheManager.instance.getTownCache()
                .getMembershipsOf(player.getUniqueId())) {
            if (membership.getRole() == cn.lunadeer.dominion.doos.TownMemberDOO.TownRole.RESIDENT) continue;
            for (cn.lunadeer.dominion.doos.DominionDOO d : cn.lunadeer.dominion.doos.DominionDOO
                    .selectByTownId(membership.getTownId())) {
                if (seen.add(d.getId())) managed.add(d);
            }
        }
        for (cn.lunadeer.dominion.doos.DominionDOO d : cn.lunadeer.dominion.doos.DominionDOO
                .selectByOutpostAdmin(player.getUniqueId())) {
            if (seen.add(d.getId())) managed.add(d);
        }
        return managed;
    }

    /**
     * Lines for the "town outposts I manage" section: outposts of towns
     * where the player is mayor/assistant, plus outposts they are
     * appointed admin of. Cross-server (DB-backed); entries on other
     * servers are display-only.
     */
    private List<Line> buildManagedTownLines(Player player) throws Exception {
        List<Line> lines = new ArrayList<>();
        java.util.Set<Integer> own = new java.util.HashSet<>();
        for (DominionDTO d : CacheManager.instance.getCache().getDominionCache()
                .getPlayerOwnDominionDTOs(player.getUniqueId())) {
            own.add(d.getId());
        }
        List<cn.lunadeer.dominion.doos.DominionDOO> managed = new ArrayList<>(managedOutposts(player));
        managed.removeIf(d -> own.contains(d.getId()));
        if (managed.isEmpty()) {
            return lines;
        }
        lines.add(Line.create().append(""));
        lines.add(Line.create().append(Component.text(
                TextUserInterface.dominionListTuiText.townManagedSection, ViewStyles.PRIMARY)));
        for (cn.lunadeer.dominion.doos.DominionDOO outpost : managed) {
            Line line = Line.create();
            if (outpost.getServerId() == Configuration.multiServer.serverId) {
                line.append(DominionManage.button(player, outpost.getName()).build());
            }
            cn.lunadeer.dominion.doos.TownDOO town = CacheManager.instance.getTownCache()
                    .getTown(outpost.getTownId());
            line.append(formatString(TextUserInterface.dominionListTuiText.townManagedLine,
                    outpost.getName(), town == null ? "?" : town.getName(), outpost.getServerId()));
            lines.add(line);
        }
        return lines;
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class DominionListCui extends ConfigurationPart {
        public String title = "§6✦ §b§lDominions List §6✦";
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

        public ButtonConfiguration ownDominionButton = ButtonConfiguration.createMaterial(
                'i', Material.GRASS_BLOCK, "§2👑 §a{0}",
                List.of(
                        "§7This is §ayour dominion§7.",
                        "§8You have full control here!",
                        "",
                        "§a▶ Left Click: manage",
                        "§8  Settings, members, permissions",
                        "§b▶ Right Click: teleport",
                        "",
                        "§7Status: §2Owner"
                )
        );

        public ButtonConfiguration adminDominionButton = ButtonConfiguration.createMaterial(
                'i', Material.DIRT_PATH, "§9⚡ §b{0}",
                List.of(
                        "§7Owner: §e{0}",
                        "§8You have admin access here.",
                        "",
                        "§b▶ Left Click: manage",
                        "§8  Help the owner manage this dominion",
                        "§b▶ Right Click: teleport",
                        "",
                        "§7Status: §9Administrator"
                )
        );

        public ButtonConfiguration contributedDominionButton = ButtonConfiguration.createMaterial(
                'i', Material.BROWN_BANNER, "§6🏰 §e{0}",
                List.of(
                        "§7Town: §b{0}",
                        "§8Your land belongs to a town now.",
                        "",
                        "§b▶ Left Click: info/manage",
                        "§b▶ Right Click: teleport",
                        "",
                        "§7Status: §6Contributed to town"
                )
        );

        public ButtonConfiguration townManagedButton = ButtonConfiguration.createMaterial(
                'i', Material.BEACON, "§3⛨ §b{0}",
                List.of(
                        "§7Town: §b{0}",
                        "§7Server: §b{1}",
                        "",
                        "§b▶ Left Click: manage (this server)",
                        "",
                        "§7Status: §3Town outpost you manage"
                )
        );

        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE,
                "§c« Back to Main Menu",
                List.of(
                        "§7Return to the main menu",
                        "§8to access other features.",
                        "",
                        "§e▶ Click to go back"
                )
        );
    }

    @Override
    protected void showCUI(Player player, String... args) {
        // town era: same migration nag as the TUI notice line — CUI
        // layouts are config files, so a chat warning is the reliable
        // channel that works on every server regardless of layout
        if (hasLegacyDominion(player)) {
            cn.lunadeer.dominion.utils.Notification.warn(
                    player, TextUserInterface.dominionListTuiText.legacyNotice);
        }
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(ChestUserInterface.dominionListCui.title);
        view.applyListConfiguration(ChestUserInterface.dominionListCui.listConfiguration, toIntegrity(args[0]));

        List<DominionDTO> own = Configuration.multiServer.enable
                // multi-server: show land on every backend; remote rows are
                // info-only (manage/teleport are same-server operations)
                ? CacheManager.instance.getPlayerOwnDominionDTOs(player.getUniqueId())
                : CacheManager.instance.getCache().getDominionCache().getPlayerOwnDominionDTOs(player.getUniqueId());
        for (DominionDTO dominion : own) {
            // town era: contributed land stays listed (the player is the
            // recorded contributor) but carries the town tag; left click
            // opens manage only with real authority, info otherwise
            boolean townOutpost = dominion instanceof cn.lunadeer.dominion.doos.DominionDOO doo
                    && doo.isTownOutpost();
            boolean remote = dominion.getServerId() != Configuration.multiServer.serverId;
            boolean canManage = !remote
                    && cn.lunadeer.dominion.misc.Asserts.checkDominionAdmin(player, dominion);
            ChestButton btn = new ChestButton(townOutpost
                    ? ChestUserInterface.dominionListCui.contributedDominionButton
                    : ChestUserInterface.dominionListCui.ownDominionButton) {
                @Override
                public void onClick(ClickType type) {
                    if (type.isLeftClick()) {
                        if (canManage) {
                            DominionManage.show(player, dominion.getName(), "1");
                        } else {
                            cn.lunadeer.dominion.uis.dominion.manage.Info.show(player, dominion.getName());
                        }
                    } else if (type.isRightClick() && !remote) {
                        teleportToDominion(player, dominion);
                    }
                }
            };
            String townName = "?";
            if (townOutpost) {
                cn.lunadeer.dominion.doos.TownDOO t = CacheManager.instance.getTownCache()
                        .getTown(((cn.lunadeer.dominion.doos.DominionDOO) dominion).getTownId());
                if (t != null) townName = t.getName();
            }
            btn = btn.setDisplayNameArgs(remote
                    ? dominion.getName() + " §8@" + dominion.getServerId()
                    : dominion.getName());
            if (townOutpost && remote) {
                btn = btn.setLoreArgs(townName + " §8| server " + dominion.getServerId());
            } else if (townOutpost) {
                btn = btn.setLoreArgs(townName);
            } else if (remote) {
                btn = btn.setLoreArgs("server " + dominion.getServerId());
            }
            view = view.addItem(btn);
        }

        // town outposts I manage (mayor/assistant/outpost admin), this server only for clicks
        try {
            for (cn.lunadeer.dominion.doos.DominionDOO outpost : managedOutposts(player)) {
                if (own.stream().anyMatch(d -> d.getId().equals(outpost.getId()))) continue;
                cn.lunadeer.dominion.doos.TownDOO t = CacheManager.instance.getTownCache()
                        .getTown(outpost.getTownId());
                ChestButton btn = new ChestButton(ChestUserInterface.dominionListCui.townManagedButton) {
                    @Override
                    public void onClick(ClickType type) {
                        if (type.isLeftClick()
                                && outpost.getServerId() == Configuration.multiServer.serverId) {
                            DominionManage.show(player, outpost.getName(), "1");
                        }
                    }
                };
                btn = btn.setDisplayNameArgs(outpost.getName());
                btn = btn.setLoreArgs(t == null ? "?" : t.getName(),
                        String.valueOf(outpost.getServerId()));
                view = view.addItem(btn);
            }
        } catch (Exception e) {
            Notification.error(player, e);
        }

        List<DominionDTO> admin = CacheManager.instance.getCache().getDominionCache().getPlayerAdminDominionDTOs(player.getUniqueId());
        for (DominionDTO dominion : admin) {
            ChestButton btn = new ChestButton(ChestUserInterface.dominionListCui.adminDominionButton) {
                @Override
                public void onClick(ClickType type) {
                    if (type.isLeftClick()) {
                        DominionManage.show(player, dominion.getName(), "1");
                    } else if (type.isRightClick()) {
                        teleportToDominion(player, dominion);
                    }
                }
            };
            btn = btn.setDisplayNameArgs(dominion.getName());
            btn = btn.setLoreArgs(List.of(dominion.getOwnerDTO().getLastKnownName()));
            view = view.addItem(btn);
        }

        view.setButton(ChestUserInterface.dominionListCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.dominionListCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        MainMenu.show(player, "1");
                    }
                }
        );

        view.open();
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        AllDominion.show(sender, args[0]);
    }

}
