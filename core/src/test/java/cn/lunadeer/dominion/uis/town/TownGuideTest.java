package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.utils.configuration.ConfigurationManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TownGuideTest {
    private TownGuideText originalText;
    private Configuration.Town originalTown;
    private String originalTool;

    @BeforeEach
    void prepare() {
        originalText = Language.townGuideText;
        originalTown = Configuration.town;
        originalTool = Configuration.selectTool;
        Language.townGuideText = new TownGuideText();
        Configuration.town = new Configuration.Town();
    }

    @AfterEach
    void restore() {
        Language.townGuideText = originalText;
        Configuration.town = originalTown;
        Configuration.selectTool = originalTool;
    }

    @Test
    void everyLocaleHasTheWholeSchemaAndResolvablePlaceholders() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        ConfigurationManager.writeConfigurationPart(defaults, new TownGuideText(), "town-guide-text");
        Set<String> expected = leafKeys(defaults.getConfigurationSection("town-guide-text"));
        Pattern token = Pattern.compile("\\{[a-z][a-z_0-9]*}");
        for (String locale : List.of("en_us", "zh_cn", "zh_tw", "jp_jp")) {
            YamlConfiguration yaml = load(locale);
            assertEquals(expected, leafKeys(yaml.getConfigurationSection("town-guide-text")), locale);
            Language.townGuideText = (TownGuideText) ConfigurationManager.readConfigurationPart(
                    yaml, new TownGuideText(), "town-guide-text");
            for (TownGuide.Topic topic : TownGuide.Topic.values()) {
                if (topic == TownGuide.Topic.CONTENTS) continue;
                TownGuideText.Article article = TownGuide.article(topic);
                assertFalse(article.title.isBlank(), locale + ": " + topic);
                assertFalse(article.summary.isBlank(), locale + ": " + topic);
                assertTrue(article.sections.size() >= 4, locale + ": " + topic);
                for (String section : TownGuide.renderedSections(topic, "测试镇")) {
                    assertFalse(token.matcher(section).find(), locale + ": " + section);
                    assertTrue(section.contains("\n"), "Each card must have a heading and body");
                    assertFalse(section.endsWith("\n"), "No empty card body");
                    assertTrue(TownGuide.wrap(section, 44).size() <= 14, "Tooltip too tall: " + section);
                }
            }
        }
    }

    @Test
    void englishResourceMatchesCodeDefaults() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        ConfigurationManager.writeConfigurationPart(defaults, new TownGuideText(), "town-guide-text");
        var source = load("en_us");
        for (String key : leafKeys(defaults)) assertEquals(defaults.get(key), source.get(key), key);
    }

    @Test
    void customConfigurationIsReadFreshInsteadOfHardcoded() {
        Configuration.selectTool = "STICK";
        Configuration.town.outpostMaxCount = -1;
        Configuration.town.outpostMaxCountPerWorld = 7;
        Configuration.town.outpostCountPerLevel = 4;
        Configuration.town.outpostMaxHorizontalSize = 800;
        Configuration.town.outpostNoLowerThan = -64;
        Configuration.town.outpostNoHigherThan = 320;
        Configuration.town.outpostPricing.pricePerBlock500To1000 = 0.5;
        Configuration.town.outpostPricing.refundRate = 0.4;
        Configuration.town.trustEnabled = false;
        Configuration.town.disbandPolicy = "return-contributed";
        Map<String, String> values = TownGuide.configurationTokens();
        assertEquals("Stick (STICK)", values.get("tool"));
        assertEquals("unlimited", values.get("base_limit"));
        assertEquals("7", values.get("base_world_limit"));
        assertEquals("4", values.get("per_level"));
        assertEquals("800", values.get("max_xz"));
        assertEquals("-64", values.get("min_y"));
        assertEquals("320", values.get("max_y"));
        assertEquals("0.5", values.get("price_1"));
        assertEquals("40%", values.get("refund_rate"));
        assertEquals("disabled", values.get("trust_enabled"));
        assertEquals(Language.townGuideText.returnContributed, values.get("disband_policy"));
        Configuration.selectTool = "ARROW";
        assertEquals("ARROW", TownGuide.configurationTokens().get("tool"));
    }

    @Test
    void tasksFollowTheConfiguredMaterialsAmountsAndOpenLevels() {
        Configuration.town.progressionMaxLevel = 3;
        var task = new Configuration.Town.TownLevelRequirement();
        task.material = "DIAMOND";
        task.amount = 123;
        Configuration.town.levelRequirements.put(2, task);
        Configuration.town.levelRequirements.put(4, task);
        assertEquals("Level 2: DIAMOND x 123", TownGuide.configurationTokens().get("tasks"));
        Configuration.town.levelRequirements.clear();
        assertEquals(Language.townGuideText.noTasks, TownGuide.configurationTokens().get("tasks"));
    }

    @Test
    void replacementsAreSinglePassAndPreserveUntrustedNamesLiterally() {
        assertEquals("Join $\\{price_1}; price 2", TownGuide.render("Join {town}; price {price_1}",
                Map.of("town", "$\\{price_1}", "price_1", "2")));
        assertTrue(TownGuide.renderedSections(TownGuide.Topic.START, "桃花镇").stream()
                .anyMatch(section -> section.contains("/town join 桃花镇")));
        assertTrue(TownGuide.renderedSections(TownGuide.Topic.START, null).stream()
                .anyMatch(section -> section.contains("/town join " + Language.townGuideText.exampleTown)));
    }

    @Test
    void wrappingPreservesTextAndDoesNotSplitUnicodePairs() {
        String text = "模板权限😀abc既有领地12345";
        List<String> lines = TownGuide.wrap(text, 8);
        assertEquals(text, String.join("", lines));
        for (String line : lines) {
            assertTrue(line.codePoints().map(c -> c > 255 ? 2 : 1).sum() <= 8);
            assertFalse(Character.isLowSurrogate(line.charAt(0)));
            assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)));
        }
        assertEquals(List.of("heading", "", "body"), TownGuide.wrap("heading\n\nbody", 20));
        assertEquals(List.of("费用 ", "0.0133"), TownGuide.wrap("费用 0.0133", 8));
        assertEquals(List.of("输入 ", "/town ", "join"), TownGuide.wrap("输入 /town join", 8));
    }

    @Test
    void pageInputsAreBoundedWithoutOverflow() {
        assertEquals(1, TownGuide.boundedPage("-999", 32, 8));
        assertEquals(1, TownGuide.boundedPage("invalid", 32, 8));
        assertEquals(4, TownGuide.boundedPage("2147483647", 32, 8));
        assertEquals(1, TownGuide.boundedPage("2", 0, 8));
        assertEquals(2, TownGuide.boundedPage("2", 32, 8));
    }

    @Test
    void lessonsCanBeReadInOrderWithoutSkippingOrLosingTheWayBack() {
        for (TownGuide.Topic topic : TownGuide.Topic.values()) {
            if (topic == TownGuide.Topic.CONTENTS) continue;
            var sections = TownGuide.article(topic).sections;
            for (int page = 1; page <= sections.size(); page++) {
                var links = TownGuide.readingLinks(topic, page);
                var previous = links.stream().filter(TownGuide.ReadingLink::previous).toList();
                assertEquals(page > 1 ? 1 : 0, previous.size());
                if (page > 1) {
                    assertEquals(topic, previous.get(0).topic());
                    assertEquals(page - 1, previous.get(0).page());
                }
                var next = links.stream().filter(link -> !link.previous()).toList();
                if (page < sections.size()) {
                    assertEquals(1, next.size());
                    assertEquals(topic, next.get(0).topic());
                    assertEquals(page + 1, next.get(0).page());
                    assertTrue(sections.get(page).startsWith(next.get(0).heading() + "\n"));
                } else if (topic != TownGuide.Topic.FAQ) {
                    assertEquals(TownGuide.Topic.values()[topic.ordinal() + 1], next.get(0).topic());
                    assertEquals(1, next.get(0).page());
                } else assertTrue(next.isEmpty());
            }
        }
    }

    private static YamlConfiguration load(String locale) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(Path.of(System.getProperty("dominion.languageDirectory"), locale + ".yml").toFile());
        return yaml;
    }

    private static Set<String> leafKeys(ConfigurationSection yaml) {
        assertNotNull(yaml);
        return yaml.getKeys(true).stream().filter(key -> !yaml.isConfigurationSection(key)).collect(Collectors.toSet());
    }
}
