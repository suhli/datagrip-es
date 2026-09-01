package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/** Replaces JSON object/array spans in-place; request lines and comments are untouched. */
public final class EsRestDocumentFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter PRETTY = JSON.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private EsRestDocumentFormatter() {}

    public static String format(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;

        StringBuilder out = new StringBuilder(text.length() + 64);
        int index = 0;
        while (index < text.length()) {
            int jsonStart = findNextJsonStart(text, index);
            if (jsonStart < 0) {
                out.append(text, index, text.length());
                break;
            }
            out.append(text, index, jsonStart);
            int jsonEnd = findBalancedJsonEnd(text, jsonStart);
            if (jsonEnd < 0) {
                out.append(text, jsonStart, text.length());
                break;
            }
            String json = text.substring(jsonStart, jsonEnd + 1);
            String pretty = prettyJson(json);
            out.append(pretty == null ? json : pretty);
            index = jsonEnd + 1;
        }
        return out.toString();
    }

    private static String prettyJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return PRETTY.writeValueAsString(node);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static int findNextJsonStart(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') continue;
            if (isCommentLineStart(text, i)) continue;
            return i;
        }
        return -1;
    }

    private static boolean isCommentLineStart(String text, int offset) {
        int lineStart = lineStart(text, offset);
        int i = lineStart;
        while (i < offset && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= offset) return false;
        if (text.charAt(i) == '#') return true;
        return i + 1 < text.length()
                && text.charAt(i) == '/'
                && text.charAt(i + 1) == '/';
    }

    private static int findBalancedJsonEnd(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int lineStart(String text, int offset) {
        int i = Math.max(0, offset - 1);
        while (i > 0 && text.charAt(i - 1) != '\n' && text.charAt(i - 1) != '\r') i--;
        return i;
    }
}
