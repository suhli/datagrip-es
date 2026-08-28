package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses Kibana Console-style REST requests. */
public final class RestRequestParser {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
    private static final ObjectMapper JSON = new ObjectMapper();

    private RestRequestParser() {}

    public static ParsedRequest parse(String text) throws SQLException {
        List<ParsedRequest> requests = parseAll(text);
        if (requests.size() != 1) throw syntax("Expected exactly one REST request");
        return requests.get(0);
    }

    public static List<ParsedRequest> parseAll(String text) throws SQLException {
        if (text == null) throw syntax("Empty request");
        List<ParsedRequest> requests = new ArrayList<>();
        int cursor = skipIgnorable(text, 0);
        while (cursor < text.length()) {
            int lineEnd = lineEnd(text, cursor);
            String requestLine = stripRequestLineComment(text.substring(cursor, lineEnd)).trim();
            int separator = firstWhitespace(requestLine);
            if (separator < 0) throw syntax("Expected HTTP method and request path");
            String method = requestLine.substring(0, separator).toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) throw syntax("Expected HTTP method");
            String path = requestLine.substring(separator).trim();
            if (!path.startsWith("/")) throw syntax("Request path must start with /");

            cursor = skipIgnorable(text, lineEnd);
            String body = null;
            if (cursor < text.length() && !looksLikeRequestLine(text, cursor)) {
                char first = text.charAt(cursor);
                if (first != '{' && first != '[') {
                    throw syntax("Expected JSON body or another HTTP request");
                }
                int bodyEnd = scanJsonValue(text, cursor);
                body = stripJsonComments(text.substring(cursor, bodyEnd));
                try {
                    JSON.readTree(body);
                } catch (Exception e) {
                    throw new SQLException("Invalid JSON request body: " + e.getMessage(), "42000", e);
                }
                cursor = skipIgnorable(text, bodyEnd);
            }
            requests.add(new ParsedRequest(method, path, body));
        }
        if (requests.isEmpty()) throw syntax("Empty request");
        return List.copyOf(requests);
    }

    private static boolean looksLikeRequestLine(String text, int start) {
        int end = lineEnd(text, start);
        String line = stripRequestLineComment(text.substring(start, end)).trim();
        int separator = firstWhitespace(line);
        if (separator < 0) return false;
        String candidate = line.substring(0, separator).toUpperCase(Locale.ROOT);
        String path = line.substring(separator).trim();
        return METHODS.contains(candidate) && path.startsWith("/");
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private static int lineEnd(String text, int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
        return end;
    }

    private static String stripRequestLineComment(String line) {
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) == '/' && line.charAt(i + 1) == '/'
                    && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int skipIgnorable(String text, int cursor) {
        while (cursor < text.length()) {
            if (Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
                continue;
            }
            if (text.charAt(cursor) == '#') {
                cursor = lineEnd(text, cursor);
                continue;
            }
            if (cursor + 1 < text.length()
                    && text.charAt(cursor) == '/' && text.charAt(cursor + 1) == '/') {
                cursor = lineEnd(text, cursor);
                continue;
            }
            break;
        }
        return cursor;
    }

    private static String stripJsonComments(String text) {
        StringBuilder result = new StringBuilder(text.length());
        boolean string = false;
        boolean escaped = false;
        boolean comment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (comment) {
                if (c == '\r' || c == '\n') {
                    comment = false;
                    result.append(c);
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (string) {
                result.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') {
                string = true;
                result.append(c);
            } else if (c == '#') {
                comment = true;
                result.append(' ');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                comment = true;
                result.append("  ");
                i++;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static int scanJsonValue(String text, int start) throws SQLException {
        int objectDepth = 0, arrayDepth = 0;
        boolean string = false, escaped = false, comment = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (comment) {
                if (c == '\r' || c == '\n') comment = false;
                continue;
            }
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') string = true;
            else if (c == '#') comment = true;
            else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                comment = true;
                i++;
            }
            else if (c == '{') objectDepth++;
            else if (c == '}') objectDepth--;
            else if (c == '[') arrayDepth++;
            else if (c == ']') arrayDepth--;
            if (objectDepth < 0 || arrayDepth < 0) throw syntax("Unbalanced JSON body");
            if (objectDepth == 0 && arrayDepth == 0) return i + 1;
        }
        throw syntax("Unterminated JSON body");
    }

    private static SQLException syntax(String message) {
        return new SQLException(message, "42000");
    }

    public record ParsedRequest(String method, String path, String body) {}
}
