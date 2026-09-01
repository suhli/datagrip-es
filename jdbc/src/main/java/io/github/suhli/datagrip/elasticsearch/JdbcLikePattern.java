package io.github.suhli.datagrip.elasticsearch;

import java.util.regex.Pattern;

/**
 * JDBC LIKE pattern matching with {@code \} as the search-string escape.
 * Unescaped {@code %} / {@code _} are wildcards; escaped forms are literals.
 */
final class JdbcLikePattern {
    static final char ESCAPE = '\\';

    private JdbcLikePattern() {}

    static boolean matches(String value, String pattern) {
        if (pattern == null || pattern.equals("%")) return true;
        if (value == null) return false;
        return toRegex(pattern).matcher(value).matches();
    }

    /** True when the pattern contains an unescaped {@code %} or {@code _}. */
    static boolean hasUnescapedWildcards(String pattern) {
        if (pattern == null) return true;
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == ESCAPE) {
                escaped = true;
                continue;
            }
            if (c == '%' || c == '_') return true;
        }
        return false;
    }

    /** True when the pattern contains an unescaped {@code %}. */
    static boolean hasUnescapedPercent(String pattern) {
        if (pattern == null) return true;
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == ESCAPE) {
                escaped = true;
                continue;
            }
            if (c == '%') return true;
        }
        return false;
    }

    /**
     * Resolves a JDBC pattern to a concrete literal when it has no unescaped
     * wildcards (after applying escape rules). Returns null if wildcards remain.
     */
    static String literalOrNull(String pattern) {
        if (pattern == null) return null;
        if (hasUnescapedWildcards(pattern)) return null;
        StringBuilder literal = new StringBuilder(pattern.length());
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                literal.append(c);
                escaped = false;
                continue;
            }
            if (c == ESCAPE) {
                escaped = true;
                continue;
            }
            literal.append(c);
        }
        // Trailing escape is treated as a literal escape character.
        if (escaped) literal.append(ESCAPE);
        return literal.toString();
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder regex = new StringBuilder("(?i)");
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaped = false;
                continue;
            }
            if (c == ESCAPE) {
                escaped = true;
                continue;
            }
            if (c == '%') regex.append(".*");
            else if (c == '_') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        if (escaped) regex.append(Pattern.quote(String.valueOf(ESCAPE)));
        return Pattern.compile(regex.toString());
    }
}
