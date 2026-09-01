package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates the small SELECT subset emitted by DataGrip's table data editor.
 * This does not use the Elasticsearch SQL API.
 */
final class SqlSelectTranslator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDENTIFIER =
            "(?:\"(?:[^\"]|\"\")+\"|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\]|[^\\s;]+)";
    private static final String QUALIFIED_IDENTIFIER =
            IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*";
    private static final Pattern SELECT = Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+(" + QUALIFIED_IDENTIFIER + ")(.*?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT_ONE = Pattern.compile(
            "^\\s*SELECT\\s+1(?:\\s*;)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile(
            "(?is)\\s+LIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+(\\d+))?\\s*$");
    private static final Pattern OFFSET_FETCH = Pattern.compile(
            "(?is)\\s+OFFSET\\s+(\\d+)\\s+ROWS?\\s+FETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY\\s*$");
    private static final Pattern ORDER_BY = Pattern.compile(
            "(?is)^(.*?)\\s+ORDER\\s+BY\\s+(" + IDENTIFIER
                    + ")(?:\\s+(ASC|DESC))?\\s*$");
    private static final Pattern COUNT = Pattern.compile(
            "(?is)^\\s*COUNT\\s*\\(\\s*\\*\\s*\\)(?:\\s+(?:AS\\s+)?"
                    + IDENTIFIER + ")?\\s*$");
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(?is)^\\s+(?:AS\\s+)?(" + IDENTIFIER + ")"
                    + "(?=\\s+(?:WHERE|ORDER\\s+BY|LIMIT|OFFSET)\\b|\\s*$)(.*)$");

    private SqlSelectTranslator() {}

    static RestRequestParser.ParsedRequest translate(String text, int maxRows, int fetchSize)
            throws SQLException {
        return translate(text, maxRows, fetchSize, null);
    }

    static RestRequestParser.ParsedRequest translate(
            String text, int maxRows, int fetchSize, EsVersion version) throws SQLException {
        if (SELECT_ONE.matcher(text).matches()) {
            return new RestRequestParser.ParsedRequest("GET", "/", null);
        }

        Matcher select = SELECT.matcher(text);
        if (!select.matches()) return null;

        String projection = select.group(1).trim();
        String index = lastIdentifierPart(select.group(2));
        validateIndex(index);
        String tail = select.group(3);
        Matcher alias = TABLE_ALIAS.matcher(tail);
        if (alias.matches() && !isClauseKeyword(alias.group(1))) {
            tail = alias.group(2);
        }

        int size = maxRows > 0 ? maxRows : fetchSize > 0 ? fetchSize : 500;
        int from = 0;

        Matcher limit = LIMIT.matcher(tail);
        if (limit.find()) {
            size = boundedInt(limit.group(1), "LIMIT");
            if (limit.group(2) != null) from = boundedInt(limit.group(2), "OFFSET");
            tail = tail.substring(0, limit.start());
        } else {
            Matcher offsetFetch = OFFSET_FETCH.matcher(tail);
            if (offsetFetch.find()) {
                from = boundedInt(offsetFetch.group(1), "OFFSET");
                size = boundedInt(offsetFetch.group(2), "FETCH");
                tail = tail.substring(0, offsetFetch.start());
            }
        }

        String orderField = null;
        String orderDirection = null;
        if (!tail.isBlank()) {
            Matcher order = ORDER_BY.matcher(tail);
            if (order.matches()) {
                tail = order.group(1);
                orderField = lastIdentifierPart(order.group(2));
                orderDirection = order.group(3) == null
                    ? "asc"
                    : order.group(3).toLowerCase(Locale.ROOT);
            }
        }

        Map<String, Object> query = null;
        String remaining = tail.trim();
        if (!remaining.isEmpty()) {
            if (!remaining.regionMatches(true, 0, "WHERE", 0, 5)
                    || (remaining.length() > 5 && !Character.isWhitespace(remaining.charAt(5)))) {
                throw unsupportedClause(remaining);
            }
            String kql = remaining.substring(5).trim();
            if (kql.isEmpty()) {
                throw new SQLException("Expected KQL expression after WHERE", "42000");
            }
            query = KqlParser.parse(kql, version);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (COUNT.matcher(projection).matches()) {
            if (orderField != null) throw unsupportedClause("ORDER BY");
            if (query != null) body.put("query", query);
            return request(body.isEmpty() ? "GET" : "POST", "/" + index + "/_count", body);
        }

        body.put("from", from);
        body.put("size", size);
        if (query != null) body.put("query", query);
        if (orderField != null) body.put("sort", List.of(Map.of(orderField, orderDirection)));
        return request("POST", "/" + index + "/_search", body);
    }

    private static RestRequestParser.ParsedRequest request(
            String method, String path, Map<String, Object> body) throws SQLException {
        try {
            return new RestRequestParser.ParsedRequest(
                    method, path, body.isEmpty() ? null : JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to create Elasticsearch search request", "HY000", e);
        }
    }

    private static int boundedInt(String value, String clause) throws SQLException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) parsed;
        } catch (NumberFormatException e) {
            throw new SQLException(clause + " value is too large", "42000", e);
        }
    }

    private static String lastIdentifierPart(String identifier) {
        String value = identifier.trim();
        int lastDot = -1;
        boolean quoted = false;
        char quoteEnd = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!quoted && (c == '"' || c == '`' || c == '[')) {
                quoted = true;
                quoteEnd = c == '[' ? ']' : c;
            } else if (quoted && c == quoteEnd) {
                quoted = false;
            } else if (!quoted && c == '.') {
                lastDot = i;
            }
        }
        value = value.substring(lastDot + 1).trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                value = value.substring(1, value.length() - 1);
                if (first == '"') value = value.replace("\"\"", "\"");
                if (first == '`') value = value.replace("``", "`");
                if (first == '[') value = value.replace("]]", "]");
            }
        }
        return value;
    }

    private static void validateIndex(String index) throws SQLException {
        if (index.isBlank() || index.indexOf('/') >= 0 || index.indexOf('?') >= 0
                || index.indexOf('#') >= 0) {
            throw new SQLException("Invalid Elasticsearch index name", "42000");
        }
    }

    private static boolean isClauseKeyword(String value) {
        String keyword = lastIdentifierPart(value);
        return keyword.equalsIgnoreCase("WHERE")
                || keyword.equalsIgnoreCase("ORDER")
                || keyword.equalsIgnoreCase("LIMIT")
                || keyword.equalsIgnoreCase("OFFSET");
    }

    private static SQLFeatureNotSupportedException unsupportedClause(String clause) {
        return new SQLFeatureNotSupportedException(
                "This SQL clause is not supported by the local REST translator; "
                        + "use an Elasticsearch REST request instead: " + clause.trim(),
                "0A000");
    }
}
