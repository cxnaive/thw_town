package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownLevelManager;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.MainMenu;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.ChestView;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.stui.View;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.misc.Others.bypassLimit;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Read-only handbook. Destinations are code-defined UI links, never commands from language files. */
public final class TownGuide extends AbstractUI {
    public enum Topic {
        CONTENTS(Material.BOOK), START(Material.COMPASS), ROLES(Material.PLAYER_HEAD),
        TEMPLATES(Material.WRITABLE_BOOK), TRUST(Material.NAME_TAG), LAND(Material.GRASS_BLOCK),
        LEVELS(Material.EXPERIENCE_BOTTLE), FAQ(Material.KNOWLEDGE_BOOK);

        final Material icon;
        Topic(Material icon) { this.icon = icon; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Integer townId;
    private final Runnable returnTo;
    private ChestView openView;
    private static final Pattern TOKEN = Pattern.compile("\\{[a-z][a-z_0-9]*}");
    private static final Pattern WORD = Pattern.compile("[\\x21-\\x7E]+|.");

    private TownGuide(Integer townId, Runnable returnTo) {
        this.townId = townId;
        this.returnTo = returnTo;
    }

    public static List<String> topics() {
        return Arrays.stream(Topic.values()).map(Topic::id).toList();
    }

    public static void show(CommandSender sender, String topic, String page) {
        new TownGuide(null, null).display(sender, topic, page);
    }

    private void display(CommandSender sender, String topic, String page) {
        Runnable render = () -> displayByPreference(sender, topic, page);
        // Commands, chat buttons and inventory callbacks all enter the owning player region.
        if (sender instanceof Player player) Scheduler.runEntityTask(render, player);
        else render.run();
    }

    public static ListViewButton button(Player player, Topic topic, Integer townId, Runnable back) {
        TownGuide guide = new TownGuide(townId, back);
        return guide.pageButton(player, topic,
                topic == Topic.CONTENTS ? Language.townGuideText.button : Language.townGuideText.pageHelp);
    }

    /** The border slot is found in the current layout, so existing customized layouts need no migration. */
    public static void addHelpButton(ChestView view, Player player, Topic topic, Integer townId, Runnable back) {
        TownGuide guide = new TownGuide(townId, back);
        view.setButtonInEmptySlot(card('?', Material.BOOK,
                topic == Topic.CONTENTS ? Language.townGuideText.title : Language.townGuideText.pageHelp,
                wrap(Language.townGuideText.description, 44),
                () -> guide.display(player, topic.id(), "1")));
    }

    private ListViewButton pageButton(Player player, Topic topic, String label) {
        return (ListViewButton) new ListViewButton(label) {
            @Override public void function(String page) { display(player, topic.id(), page); }
        }.needPermission(defaultPermission).setHoverText(Language.townGuideText.description);
    }

    private Topic topic(CommandSender sender, String value) {
        try {
            return Topic.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Notification.warn(sender, Language.townGuideText.unknownTopic, value);
            return Topic.CONTENTS;
        }
    }

    static TownGuideText.Article article(Topic topic) {
        TownGuideText text = Language.townGuideText;
        return switch (topic) {
            case START -> text.start;
            case ROLES -> text.roles;
            case TEMPLATES -> text.templates;
            case TRUST -> text.trust;
            case LAND -> text.land;
            case LEVELS -> text.levels;
            case FAQ -> text.faq;
            case CONTENTS -> throw new IllegalArgumentException("Contents is not an article");
        };
    }

    private TownDOO town(Player player) {
        return townId == null ? TownManager.ownTownOf(player)
                : CacheManager.instance.getTownCache().getTown(townId);
    }

    private String context(TownDOO town) {
        TownGuideText text = Language.townGuideText;
        return town == null ? text.noTown : formatString(text.context, town.getName());
    }

    private void back(Player player) {
        if (openView != null) openView.close();
        if (returnTo != null) returnTo.run();
        else MainMenu.show(player, "1");
    }

    @Override
    protected void showTUI(Player player, String... args) {
        Topic topic = topic(player, args[0]);
        TownDOO town = town(player);
        TownGuideText text = Language.townGuideText;
        View view = View.create();
        view.title(topic == Topic.CONTENTS ? text.title : article(topic).title);
        Line nav = Line.create().append(new FunctionalButton(text.back) {
            @Override public void function() { back(player); }
        }.needPermission(defaultPermission).build());
        if (topic != Topic.CONTENTS) nav.append(pageButton(player, Topic.CONTENTS, text.contents).build());
        if (topic == Topic.CONTENTS) {
            for (String line : wrap(context(town), 64)) view.addLine(line);
            for (Topic entry : Topic.values()) {
                if (entry == Topic.CONTENTS) continue;
                view.addLine(Line.create().append(pageButton(player, entry, article(entry).title).build())
                        .append(article(entry).summary));
            }
        } else {
            List<String> sections = renderedSections(topic, town == null ? null : town.getName());
            int page = boundedPage(args[1], sections.size(), 1);
            if (!sections.isEmpty()) {
                for (String line : wrap(sections.get(page - 1), 64)) view.addLine(line);
            }
            Line actions = Line.create().append(formatString(text.pageInfo, page, Math.max(1, sections.size())));
            for (ReadingLink link : readingLinks(topic, page)) actions.append(new FunctionalButton(link.label()) {
                @Override public void function() { display(player, link.topic().id(), String.valueOf(link.page())); }
            }.needPermission(defaultPermission).setHoverText(link.heading()).build());
            view.actionBar(actions);
        }
        for (Link link : links(player, town, topic)) {
            nav.append(new FunctionalButton(link.label()) {
                @Override public void function() { openLink(player, topic, link.home()); }
            }.needPermission(defaultPermission).build());
        }
        view.navigator(nav);
        view.showOn(player);
    }

    @Override
    protected void showCUI(Player player, String... args) {
        Topic topic = topic(player, args[0]);
        TownDOO town = town(player);
        TownGuideText text = Language.townGuideText;
        ChestView view = ChestUserInterfaceManager.getInstance().getViewOf(player);
        openView = view;
        view.setTitle(topic == Topic.CONTENTS ? text.title : article(topic).title);
        view.setLayout(List.of("<###c###h", "#########", "####i####", "#########", "p###s###n"));
        view.setButton('<', card('<', Material.RED_STAINED_GLASS_PANE, text.back, List.of(), () -> back(player)));
        view.setButton('c', card('c', Material.BOOK, text.contents, wrap(context(town), 44),
                () -> display(player, Topic.CONTENTS.id(), "1")));
        List<Link> links = links(player, town, topic);
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            view.setButton(i == 0 ? 8 : 7, card('h', Material.COMPASS, link.label(), List.of(),
                    () -> openLink(player, topic, link.home())));
        }
        if (topic == Topic.CONTENTS) {
            int slot = 10;
            for (Topic entry : Topic.values()) {
                if (entry == Topic.CONTENTS) continue;
                List<String> lore = new ArrayList<>(wrap(article(entry).summary, 44));
                lore.addAll(wrap(text.read, 44));
                view.setButton(slot++, card('i', entry.icon, article(entry).title, lore,
                        () -> display(player, entry.id(), "1")));
            }
        } else {
            List<String> sections = renderedSections(topic, town == null ? null : town.getName());
            int page = boundedPage(args[1], sections.size(), 1);
            if (!sections.isEmpty()) {
                String[] parts = sections.get(page - 1).split("\\n", 2);
                view.setButton('i', card('i', Material.PAPER, parts[0],
                        parts.length == 2 ? wrap(parts[1], 44) : List.of(), () -> { }));
            }
            view.setButton('s', card('s', Material.BOOK, formatString(text.pageInfo, page, Math.max(1, sections.size())),
                    List.of(article(topic).title), () -> { }));
            for (ReadingLink link : readingLinks(topic, page)) {
                char symbol = link.previous() ? 'p' : 'n';
                view.setButton(symbol, card(symbol, Material.ARROW, link.label(), wrap(link.heading(), 44),
                        () -> display(player, link.topic().id(), String.valueOf(link.page()))));
            }
        }
        // ChestView can reuse an inventory without clearing old slots. Start the reader clean,
        // otherwise a short chapter can still show items from the previous management page.
        view.close();
        view.open();
    }

    record ReadingLink(Topic topic, int page, String label, String heading, boolean previous) { }

    /** A page is a complete lesson; the last lesson leads to the next chapter. */
    static List<ReadingLink> readingLinks(Topic topic, int page) {
        List<ReadingLink> links = new ArrayList<>();
        TownGuideText text = Language.townGuideText;
        List<String> sections = article(topic).sections;
        if (page > 1) links.add(new ReadingLink(topic, page - 1, text.previousPage,
                sections.get(page - 2).split("\\n", 2)[0], true));
        if (page < sections.size()) links.add(new ReadingLink(topic, page + 1, text.nextPage,
                sections.get(page).split("\\n", 2)[0], false));
        else if (topic.ordinal() + 1 < Topic.values().length) {
            Topic next = Topic.values()[topic.ordinal() + 1];
            links.add(new ReadingLink(next, 1, text.nextChapter, article(next).title, false));
        }
        return links;
    }

    private record Link(String label, boolean home) { }

    private List<Link> links(Player player, TownDOO town, Topic topic) {
        List<Link> result = new ArrayList<>();
        if (town == null) return result;
        TownGuideText text = Language.townGuideText;
        result.add(new Link(text.openTown, true));
        boolean member = TownManager.roleOf(town, player.getUniqueId()) != null || bypassLimit(player);
        boolean manager = TownManager.isManager(town, player.getUniqueId()) || bypassLimit(player);
        if ((topic == Topic.ROLES || topic == Topic.TEMPLATES) && member) result.add(new Link(text.openGroups, false));
        if (topic == Topic.TRUST && manager) result.add(new Link(text.openTrusts, false));
        if (topic == Topic.LAND && member) result.add(new Link(text.openOutposts, false));
        if (topic == Topic.LEVELS && member) result.add(new Link(text.openTasks, false));
        return result;
    }

    private void openLink(Player player, Topic topic, boolean home) {
        TownDOO town = town(player);
        // Recheck membership at click time; a handbook never grants access to its destinations.
        if (town == null || links(player, town, topic).stream().noneMatch(link -> link.home() == home)) return;
        if (openView != null) openView.close();
        if (home) TownInfo.show(player, town.getName(), "1");
        else switch (topic) {
            case ROLES, TEMPLATES -> TownGroups.show(player, town.getName(), "1");
            case TRUST -> TownTrusts.show(player, town.getName(), "1");
            case LAND -> TownOutposts.show(player, town.getName(), "1");
            case LEVELS -> TownLevelTasks.show(player, town.getName(), "1");
            default -> { }
        }
    }

    private static ChestButton card(char symbol, Material material, String title, List<String> lore, Runnable action) {
        return new ChestButton(ButtonConfiguration.createMaterial(symbol, material, "§e" + title,
                lore.stream().map(line -> "§7" + line).toList())) {
            @Override public void onClick(ClickType type) { action.run(); }
        };
    }

    static List<String> renderedSections(Topic topic, String townName) {
        Map<String, String> values = configurationTokens();
        values.put("town", townName == null ? Language.townGuideText.exampleTown : townName);
        return article(topic).sections.stream().map(section -> render(section, values)).toList();
    }

    static String render(String source, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(source);
        // One pass: names containing placeholder-looking text are treated literally.
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
                values.getOrDefault(match.group().substring(1, match.group().length() - 1), match.group())));
    }

    static Map<String, String> configurationTokens() {
        var config = Configuration.town;
        var pricing = config.outpostPricing;
        TownGuideText text = Language.townGuideText;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tool", "STICK".equalsIgnoreCase(Configuration.selectTool)
                ? text.stick + " (STICK)" : Configuration.selectTool);
        values.put("founding_size", String.valueOf(config.foundingSize));
        values.put("founding_cost", number(config.foundingCost));
        values.put("remote_enabled", state(config.outpostAllowRemote));
        values.put("trust_enabled", state(config.trustEnabled));
        values.put("max_xz", String.valueOf(config.outpostMaxHorizontalSize));
        values.put("min_x", String.valueOf(config.outpostMinSizeX));
        values.put("min_z", String.valueOf(config.outpostMinSizeZ));
        values.put("min_height", String.valueOf(config.outpostMinSizeY));
        values.put("min_y", String.valueOf(config.outpostNoLowerThan));
        values.put("max_y", String.valueOf(config.outpostNoHigherThan));
        values.put("max_height", limit(config.outpostMaxSizeY));
        values.put("pricing_enabled", state(pricing.enable));
        values.put("price_1", number(pricing.pricePerBlock500To1000));
        values.put("price_2", number(pricing.pricePerBlock1000To1500));
        values.put("price_3", number(pricing.pricePerBlock1500To2000));
        values.put("refund_rate", number(pricing.refundRate * 100) + "%");
        values.put("base_limit", limit(config.outpostMaxCount));
        values.put("base_world_limit", limit(config.outpostMaxCountPerWorld));
        values.put("per_level", String.valueOf(config.outpostCountPerLevel));
        values.put("open_level", String.valueOf(config.progressionMaxLevel));
        values.put("max_level", String.valueOf(config.maxLevel));
        values.put("disband_policy", "return-contributed".equals(config.disbandPolicy)
                ? text.returnContributed : text.dissolveAll);
        List<String> tasks = new ArrayList<>();
        config.levelRequirements.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getKey() >= 2 && entry.getKey() <= config.progressionMaxLevel)
                .forEach(entry -> tasks.add(formatString(text.taskLine, entry.getKey(),
                        TownLevelManager.displayMaterial(Material.matchMaterial(entry.getValue().material)),
                        entry.getValue().amount)));
        values.put("tasks", tasks.isEmpty() ? text.noTasks : String.join("\n", tasks));
        return values;
    }

    private static String state(boolean value) { return value ? Language.townGuideText.enabled : Language.townGuideText.disabled; }
    private static String limit(int value) { return value == -1 ? Language.townGuideText.unlimited : String.valueOf(value); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    static int boundedPage(String page, int size, int pageSize) {
        return Math.min(Math.max(1, toIntegrity(page, 1)), Math.max(1, (size + pageSize - 1) / pageSize));
    }

    /** Keep CJK tooltips narrow and preserve explicit breaks without splitting surrogate pairs. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            StringBuilder line = new StringBuilder();
            int used = 0;
            Matcher words = WORD.matcher(paragraph);
            while (words.find()) {
                String word = words.group();
                int wordWidth = word.codePoints().map(c -> c > 255 ? 2 : 1).sum();
                // Keep command words and prices together when they fit on one line.
                if (wordWidth <= width && used + wordWidth > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                for (int codePoint : word.codePoints().toArray()) {
                    int size = codePoint > 255 ? 2 : 1;
                    if (used + size > width && !line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                        used = 0;
                    }
                    line.appendCodePoint(codePoint);
                    used += size;
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) {
        Topic topic = topic(sender, args[0]);
        Notification.info(sender, Language.townGuideText.title);
        if (topic == Topic.CONTENTS) {
            for (Topic entry : Topic.values()) {
                if (entry != Topic.CONTENTS) Notification.info(sender, "/town help {0} - {1}", entry.id(), article(entry).title);
            }
        } else {
            for (String section : renderedSections(topic, null)) Notification.info(sender, "{0}", section);
        }
    }
}
