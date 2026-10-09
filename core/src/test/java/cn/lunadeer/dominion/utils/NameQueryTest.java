package cn.lunadeer.dominion.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NameQueryTest {
    @Test void ranksExactBeforePrefixBeforeSubstringBeforeOrderedCharacters() {
        var names = List.of("黄河金色城", "新黄金城区", "黄金城市", "黄金城", "黄城", "城黄");
        assertEquals(List.of("黄金城", "黄金城市", "新黄金城区", "黄河金色城"),
                new NameQuery("黄金城").find(names, name -> name));
        assertEquals(List.of("黄金城", "黄金城市", "新黄金城区", "黄河金色城"),
                new NameQuery("黄金").find(names, name -> name));
        assertEquals(List.of("黄城", "黄金城"), new NameQuery("黄城").find(List.of("黄金城", "黄城", "城黄"), name -> name));
    }

    @Test void normalizesFullWidthCaseAndWhitespaceWithoutDependingOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("iron town", new NameQuery("　ＩＲＯＮ\tＴｏｗｎ ").text());
            assertEquals(List.of("IRON_TOWN", "IronTown"),
                    new NameQuery("iron").find(List.of("IRON_TOWN", "IronTown", "Stone"), name -> name));
        } finally { Locale.setDefault(previous); }
    }

    @Test void multipleKeywordsAllHaveToMatchAndSymbolsStayLiteral() {
        assertEquals(List.of("黄金城东区农场"), new NameQuery("黄金 农场")
                .find(List.of("黄金城东区农场", "黄金城仓库", "白银城农场"), name -> name));
        for (String literal : List.of("%", "_", "[", ".*", "' OR 1=1")) {
            assertEquals(List.of("前" + literal + "后"), new NameQuery(literal)
                    .find(List.of("普通领地", "前" + literal + "后"), name -> name));
        }
    }

    @Test void matchesUnicodeCodePointsAndDoesNotReuseCharacters() {
        assertEquals(List.of("😀的领地城"), new NameQuery("😀城")
                .find(List.of("😀的领地城", "😁的领地城"), name -> name));
        assertEquals(List.of("黄黄金"), new NameQuery("黄黄")
                .find(List.of("黄金", "黄黄金"), name -> name));
        assertEquals(List.of("家"), new NameQuery("家").find(List.of("家", "仓库"), name -> name));
    }

    @Test void boundsInputAndLeavesCandidateCollectionsUnchanged() {
        assertTrue(new NameQuery(" \t\n").isEmpty());
        assertDoesNotThrow(() -> new NameQuery("😀".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new NameQuery("a".repeat(65)));
        var names = new ArrayList<>(List.of("B", "A"));
        assertEquals(List.of("A", "B"), new NameQuery("").find(names, name -> name));
        assertEquals(List.of("B", "A"), names);
    }

    @Test void handlesTenThousandNamesWithStableResultOrdering() {
        var names = new ArrayList<String>();
        for (int i = 9999; i >= 0; i--) names.add("黄金城" + i);
        var result = new NameQuery("黄城").find(names, name -> name);
        assertEquals(10000, result.size());
        assertEquals(result.stream().sorted().toList(), result);
        assertEquals(result, new NameQuery("黄城").find(names, name -> name));
    }
}
