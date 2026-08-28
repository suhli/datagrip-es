package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/** Parses one Kibana-style REST request without accepting a hidden second request. */
public final class RestRequestParser {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
    private static final ObjectMapper JSON = new ObjectMapper();

    private RestRequestParser() {}

    public static ParsedRequest parse(String text) throws SQLException {
        if (text == null || text.isBlank()) throw syntax("Empty request");
        int cursor = skipWhitespace(text, 0);
        int methodEnd = cursor;
        while (methodEnd < text.length() && Character.isLetter(text.charAt(methodEnd))) methodEnd++;
        String method = text.substring(cursor, methodEnd).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw syntax("Expected HTTP method");
        cursor = skipHorizontal(text, methodEnd);
        if (cursor == methodEnd) throw syntax("Expected request path");
        int pathEnd = cursor;
        while (pathEnd < text.length() && text.charAt(pathEnd) != '\r' && text.charAt(pathEnd) != '\n') pathEnd++;
        String path = text.substring(cursor, pathEnd).trim();
        if (!path.startsWith("/")) throw syntax("Request path must start with /");

        cursor = skipWhitespace(text, pathEnd);
        if (cursor == text.length()) return new ParsedRequest(method, path, null);
        int bodyEnd = scanJsonValue(text, cursor);
        String body = text.substring(cursor, bodyEnd);
        try {
            JSON.readTree(body);
        } catch (Exception e) {
            throw new SQLException("Invalid JSON request body: " + e.getMessage(), "42000", e);
        }
        int trailing = skipWhitespace(text, bodyEnd);
        if (trailing != text.length()) {
            throw syntax("Multiple REST requests are not allowed");
        }
        return new ParsedRequest(method, path, body);
    }

    private static int scanJsonValue(String text, int start) throws SQLException {
        char first = text.charAt(start);
        if (first != '{' && first != '[') {
            int end = start;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            return end;
        }
        int objectDepth = 0, arrayDepth = 0;
        boolean string = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') string = true;
            else if (c == '{') objectDepth++;
            else if (c == '}') objectDepth--;
            else if (c == '[') arrayDepth++;
            else if (c == ']') arrayDepth--;
            if (objectDepth < 0 || arrayDepth < 0) throw syntax("Unbalanced JSON body");
            if (objectDepth == 0 && arrayDepth == 0) return i + 1;
        }
        throw syntax("Unterminated JSON body");
    }

    private static int skipWhitespace(String text, int cursor) {
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private static int skipHorizontal(String text, int cursor) {
        while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) cursor++;
        return cursor;
    }

    private static SQLException syntax(String message) {
        return new SQLException(message, "42000");
    }

    public record ParsedRequest(String method, String path, String body) {}
}
