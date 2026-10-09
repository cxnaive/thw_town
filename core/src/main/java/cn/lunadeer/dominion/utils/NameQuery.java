package cn.lunadeer.dominion.utils;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Literal name search: exact, prefix, substring, then ordered characters. No SQL or regex input. */
public final class NameQuery {
    public static final int MAX_LENGTH = 64;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final String query;
    private final List<String> words;

    public NameQuery(String input) {
        query = normalize(input);
        if (query.codePointCount(0, query.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("Search query exceeds " + MAX_LENGTH + " characters");
        }
        words = query.isEmpty() ? List.of() : List.of(query.split(" "));
    }

    public String text() { return query; }
    public boolean isEmpty() { return query.isEmpty(); }

    public static String normalize(String value) {
        return WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    /** Every space-separated word must match; ordered character matches rank below literal matches. */
    private int rank(String candidate) {
        if (query.isEmpty() || candidate.equals(query)) return 0;
        if (candidate.startsWith(query)) return 1;
        if (candidate.contains(query)) return 2;
        boolean allLiteral = true;
        for (String word : words) {
            if (candidate.contains(word)) continue;
            allLiteral = false;
            int offset = 0;
            for (int codePoint : word.codePoints().toArray()) {
                int next = candidate.indexOf(codePoint, offset);
                if (next < 0) return -1;
                offset = next + Character.charCount(codePoint);
            }
        }
        return allLiteral ? 3 : 4;
    }

    public <T> List<T> find(Collection<T> candidates, Function<T, String> name) {
        record Match<T>(T item, int rank, String name, String original) { }
        return candidates.stream().map(item -> {
                    String original = name.apply(item);
                    String normalized = normalize(original);
                    return new Match<>(item, rank(normalized), normalized, original);
                }).filter(match -> match.rank() >= 0)
                .sorted(Comparator.comparingInt(Match<T>::rank).thenComparing(Match::name).thenComparing(Match::original))
                .map(Match::item).toList();
    }
}
