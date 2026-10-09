package cn.lunadeer.dominion.uis;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.MultiServerManager;
import cn.lunadeer.dominion.managers.TeleportManager;
import cn.lunadeer.dominion.managers.WorldMapManager;
import cn.lunadeer.dominion.misc.Asserts;
import cn.lunadeer.dominion.uis.dominion.DominionList;
import cn.lunadeer.dominion.uis.dominion.DominionManage;
import cn.lunadeer.dominion.uis.dominion.manage.Info;
import cn.lunadeer.dominion.uis.town.TownInfo;
import cn.lunadeer.dominion.uis.town.TownList;
import cn.lunadeer.dominion.uis.town.TownOutposts;
import cn.lunadeer.dominion.utils.NameQuery;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.ChestView;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.stui.View;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.Pagination;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.lunadeer.dominion.Dominion.adminPermission;
import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Search is a read-only view of an existing list's scope. Matching never grants management or teleport access. */
public final class NameSearch extends AbstractUI {
    public enum Scope { TOWNS, OWN_LAND, TOWN_LAND, ADMIN_LAND }

    private final Scope scope;
    private final Integer townId;
    private final Runnable returnTo;
    private NameQuery query;
    private List<Entry> entries = List.of();
    private String scopeLabel;
    private Map<Integer, String> servers = Map.of();
    private Map<Integer, Map<UUID, String>> worlds = Map.of();

    private record Entry(String name, List<String> details, Integer townId, DominionDTO dominion) { }
    private record LandKey(int serverId, int id) { }

    public NameSearch(Scope scope, Integer townId, Runnable returnTo) {
        this.scope = scope;
        this.townId = townId;
        this.returnTo = returnTo;
    }

    private String permission() { return scope == Scope.ADMIN_LAND ? adminPermission : defaultPermission; }

    private boolean allowed(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission(permission())) return true;
        Notification.error(sender, Language.commandExceptionText.noPermission, permission());
        return false;
    }

    private static void onSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) Scheduler.runEntityTask(action, player);
        else action.run();
    }

    public FunctionalButton button(Player player) {
        return (FunctionalButton) new FunctionalButton(Language.nameSearchText.button) {
            @Override public void function() { prompt(player); }
        }.needPermission(permission()).setHoverText(Language.nameSearchText.description);
    }

    public void addButton(ChestView view, Player player) {
        view.setButtonInEmptySlot(card('?', Material.COMPASS, Language.nameSearchText.button,
                List.of(Language.nameSearchText.description), () -> prompt(player)));
    }

    private void prompt(Player player) {
        onSender(player, () -> {
            if (!allowed(player)) return;
            player.closeInventory();
            new InputterRunner(player, Language.nameSearchText.hint) {
                @Override public void run(String input) { search(player, input, "1"); }
                @Override public void cancelRun() { onSender(player, () -> back(player)); }
            };
        });
    }

    public void search(CommandSender sender, String input, String page) {
        onSender(sender, () -> {
            if (!allowed(sender)) return;
            NameQuery nextQuery;
            try {
                nextQuery = new NameQuery(input);
            } catch (IllegalArgumentException e) {
                Notification.warn(sender, Language.nameSearchText.tooLong, NameQuery.MAX_LENGTH);
                return;
            }
            if (nextQuery.isEmpty()) {
                if (sender instanceof Player player) prompt(player);
                else Notification.info(sender, scope == Scope.TOWNS
                        ? Language.nameSearchText.townUsage : Language.nameSearchText.landUsage);
                return;
            }
            // Each submitted query owns its results; pagination retains that query and ordering.
            NameSearch results = new NameSearch(scope, townId, returnTo);
            results.query = nextQuery;
            Scheduler.runTaskAsync(() -> {
                try {
                    results.load(sender);
                    onSender(sender, () -> {
                        if (results.allowed(sender)) results.displayByPreference(sender, page);
                    });
                } catch (Exception e) {
                    onSender(sender, () -> Notification.error(sender, e));
                }
            });
        });
    }

    private void load(CommandSender sender) throws Exception {
        var cache = CacheManager.instance;
        var text = Language.nameSearchText;
        if (scope == Scope.TOWNS) {
            scopeLabel = text.allTowns;
            entries = query.find(cache.getTownCache().getAllTowns(), TownDOO::getName).stream()
                    .map(town -> new Entry(town.getName(), List.of(formatString(text.townDetails,
                            cache.getPlayerName(town.getMayorUuid()), cache.getTownCache().getMembersOf(town.getId()).size())),
                            town.getId(), null)).toList();
            return;
        }
        List<DominionDTO> candidates = new ArrayList<>();
        if (scope == Scope.TOWN_LAND) {
            TownDOO town = cache.getTownCache().getTown(townId);
            if (town == null) throw new IllegalStateException(text.missing);
            scopeLabel = formatString(text.townLand, town.getName());
            candidates.addAll(DominionDOO.selectByTownId(townId));
        } else if (scope == Scope.OWN_LAND && sender instanceof Player player) {
            scopeLabel = text.ownLand;
            candidates.addAll(cache.getPlayerOwnDominionDTOs(player.getUniqueId()));
            candidates.addAll(cache.getPlayerAdminDominionDTOs(player.getUniqueId()));
            candidates.addAll(DominionList.managedTownOutposts(player));
        } else {
            scopeLabel = text.adminLand;
            candidates.addAll(cache.getCache().getDominionCache().getAllDominions());
        }
        Map<LandKey, DominionDTO> unique = new LinkedHashMap<>();
        for (DominionDTO land : candidates) {
            if (land.getId() > 0) unique.put(new LandKey(land.getServerId(), land.getId()), land);
        }
        // IDs break ties for duplicate names on different servers, without loading any world or chunk.
        List<DominionDTO> ordered = unique.values().stream().sorted(Comparator
                .comparingInt(DominionDTO::getServerId).thenComparingInt(DominionDTO::getId)).toList();
        servers = MultiServerManager.instance.getServerNames();
        worlds = WorldMapManager.getWorldNames();
        entries = query.find(ordered, DominionDTO::getName).stream().map(land -> {
            TownDOO town = land instanceof DominionDOO outpost && outpost.isTownOutpost()
                    ? cache.getTownCache().getTown(outpost.getTownId()) : null;
            String owner = town == null ? formatString(text.personalOwner, cache.getPlayerName(land.getOwner()))
                    : formatString(text.townOwner, town.getName());
            return new Entry(land.getName(), List.of(owner, formatString(text.location, server(land), world(land))), null, land);
        }).toList();
    }

    private String server(DominionDTO land) {
        return MultiServerManager.formatServerLabel(land.getServerId(), servers.getOrDefault(land.getServerId(),
                land.getServerId() == Configuration.multiServer.serverId ? Configuration.multiServer.serverName : null));
    }

    private String world(DominionDTO land) {
        return worlds.getOrDefault(land.getServerId(), Map.of())
                .getOrDefault(land.getWorldUid(), "#" + land.getWorldUid().toString().substring(0, 8));
    }

    private String title() { return scope == Scope.TOWNS ? Language.nameSearchText.townTitle : Language.nameSearchText.landTitle; }
    private String summary() { return formatString(Language.nameSearchText.summary, query.text(), entries.size()); }
    private int page(String value, int size) {
        return Math.min(Math.max(1, toIntegrity(value, 1)), Math.max(1, (entries.size() + size - 1) / size));
    }

    private void back(Player player) {
        if (!allowed(player)) return;
        player.closeInventory();
        if (returnTo != null) returnTo.run();
        else switch (scope) {
            case TOWNS -> TownList.show(player, "1");
            case OWN_LAND -> DominionList.show(player, "1");
            case ADMIN_LAND -> AllDominion.show(player, "1");
            case TOWN_LAND -> {
                TownDOO town = CacheManager.instance.getTownCache().getTown(townId);
                if (town != null) TownOutposts.show(player, town.getName(), "1");
                else TownList.show(player, "1");
            }
        }
    }

    private void open(Player player, Entry entry, boolean teleport) {
        onSender(player, () -> {
            if (!allowed(player)) return;
            if (entry.townId() != null) {
                TownDOO town = CacheManager.instance.getTownCache().getTown(entry.townId());
                if (town == null) Notification.warn(player, Language.nameSearchText.missing);
                else { player.closeInventory(); TownInfo.show(player, town.getName(), "1"); }
                return;
            }
            Scheduler.runTaskAsync(() -> {
                try {
                    DominionDOO current = DominionDOO.select(entry.dominion().getId());
                    onSender(player, () -> {
                        if (!allowed(player)) return;
                        if (current == null || !current.getServerId().equals(entry.dominion().getServerId())
                                || (scope == Scope.TOWN_LAND && !townId.equals(current.getTownId()))) {
                            Notification.warn(player, Language.nameSearchText.missing);
                        } else if (teleport) {
                            player.closeInventory();
                            TeleportManager.teleportToDominion(player, current);
                        } else if (current.getServerId() != Configuration.multiServer.serverId) {
                            Notification.info(player, Language.nameSearchText.remote, server(current), world(current));
                        } else {
                            player.closeInventory();
                            if (Asserts.checkDominionAdmin(player, current)) DominionManage.show(player, current.getName(), "1");
                            else Info.show(player, current.getName());
                        }
                    });
                } catch (Exception e) { onSender(player, () -> Notification.error(player, e)); }
            });
        });
    }

    @Override protected void showCUI(Player player, String... args) {
        var text = Language.nameSearchText;
        ChestView view = ChestUserInterfaceManager.getInstance().getViewOf(player);
        view.setTitle(title());
        view.setLayout(List.of("<##s#c###", "#iiiiiii#", "#iiiiiii#", "#iiiiiii#", "p###q###n"));
        int page = page(args[0], 21);
        String progress = formatString(text.page, page, Math.max(1, (entries.size() + 20) / 21));
        if (page > 1) view.setButton('p', card('p', Material.ARROW, text.previous, List.of(progress),
                () -> displayPage(player, String.valueOf(page - 1))));
        if (page * 21 < entries.size()) view.setButton('n', card('n', Material.ARROW, text.next, List.of(progress),
                () -> displayPage(player, String.valueOf(page + 1))));
        view.setButton('<', card('<', Material.RED_STAINED_GLASS_PANE, text.back, List.of(), () -> back(player)));
        view.setButton('c', card('c', Material.MILK_BUCKET, text.clear, List.of(scopeLabel), () -> back(player)));
        view.setButton('s', card('s', Material.COMPASS, text.change, List.of(text.description), () -> prompt(player)));
        view.setButton('q', card('q', Material.PAPER, summary(), List.of(scopeLabel, progress), () -> { }));
        if (entries.isEmpty()) view.setButton(22, card('i', Material.BARRIER, text.empty, List.of(), () -> prompt(player)));
        // Build only the visible page, even if a broad search matches thousands of names.
        List<Entry> visible = entries.subList((page - 1) * 21, Math.min(page * 21, entries.size()));
        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            List<String> lore = new ArrayList<>(entry.details());
            lore.add(entry.townId() != null ? text.townClick : text.landClick);
            view.setButton(1 + i / 7, 1 + i % 7, new ChestButton(ButtonConfiguration.createMaterial('i',
                    entry.townId() != null ? Material.GRASS_BLOCK : Material.FILLED_MAP, "§e" + entry.name(), lore)) {
                @Override public void onClick(ClickType type) {
                    if (type.isLeftClick() || (entry.dominion() != null && type.isRightClick())) open(player, entry, type.isRightClick());
                }
            });
        }
        view.close();
        view.open();
    }

    @Override protected void showTUI(Player player, String... args) {
        var text = Language.nameSearchText;
        ListViewButton pages = new ListViewButton(text.button) {
            @Override public void function(String page) { displayPage(player, page); }
        };
        View view = View.create();
        int page = page(args[0], 8);
        view.title(title() + " - " + summary());
        view.navigator(Line.create().append(action(text.back, () -> back(player)).build())
                .append(button(player).build()).append(action(text.clear, () -> back(player)).build()).append(scopeLabel));
        if (entries.isEmpty()) view.addLine(text.empty);
        for (Entry entry : entries.subList((page - 1) * 8, Math.min(page * 8, entries.size()))) {
            Line line = Line.create().append(action(entry.name(), () -> open(player, entry, false)).build())
                    .append(String.join(" | ", entry.details()));
            if (entry.dominion() != null) line.append(action(text.teleport, () -> open(player, entry, true)).build());
            view.addLine(line);
        }
        view.actionBar(Pagination.create(page, entries.size(), 8, pages));
        view.showOn(player);
    }

    private void displayPage(Player player, String page) {
        onSender(player, () -> { if (allowed(player)) displayByPreference(player, page); });
    }

    private FunctionalButton action(String label, Runnable runnable) {
        return (FunctionalButton) new FunctionalButton(label) {
            @Override public void function() { runnable.run(); }
        }.needPermission(permission());
    }

    private static ChestButton card(char symbol, Material material, String label, List<String> lore, Runnable action) {
        return new ChestButton(ButtonConfiguration.createMaterial(symbol, material, "§e" + label, lore)) {
            @Override public void onClick(ClickType type) { action.run(); }
        };
    }

    @Override protected void showConsole(CommandSender sender, String... args) {
        Notification.info(sender, "{0} - {1}", scopeLabel, summary());
        int page = page(args[0], 15);
        if (entries.isEmpty()) Notification.info(sender, Language.nameSearchText.empty);
        for (Entry entry : entries.subList((page - 1) * 15, Math.min(page * 15, entries.size()))) {
            Notification.info(sender, "{0} | {1}", entry.name(), String.join(" | ", entry.details()));
        }
        Notification.info(sender, Language.nameSearchText.page, page, Math.max(1, (entries.size() + 14) / 15));
    }
}
