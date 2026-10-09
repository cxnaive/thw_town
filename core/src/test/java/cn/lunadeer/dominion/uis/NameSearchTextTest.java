package cn.lunadeer.dominion.uis;

import cn.lunadeer.dominion.utils.configuration.ConfigurationManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NameSearchTextTest {
    @Test void everyLocaleHasStringValuesForAllSearchMessages() throws Exception {
        var defaults = new YamlConfiguration();
        ConfigurationManager.writeConfigurationPart(defaults, new NameSearchText(), "name-search-text");
        var expected = defaults.getConfigurationSection("name-search-text");
        assertNotNull(expected);
        for (String locale : List.of("en_us", "zh_cn", "zh_tw", "jp_jp")) {
            var yaml = new YamlConfiguration();
            yaml.load(Path.of(System.getProperty("dominion.languageDirectory"), locale + ".yml").toFile());
            var section = yaml.getConfigurationSection("name-search-text");
            assertNotNull(section, locale);
            assertEquals(expected.getKeys(true), section.getKeys(true), locale);
            for (String key : expected.getKeys(true)) {
                assertTrue(section.isString(key), locale + ": " + key);
                assertFalse(section.getString(key).isBlank());
                if (locale.equals("en_us")) assertEquals(expected.get(key), section.get(key), key);
            }
        }
    }
}
