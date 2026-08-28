package io.github.suhli.datagrip.elasticsearch;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses KQL and converts it to Elasticsearch Query DSL. */
final class KqlParser {
    private final List<Token> tokens;
    private int cursor;

    private KqlParser(String input) throws SQLException {
        this.tokens = tokenize(input);
    }

    static Map<String, Object> parse(String input) throws SQLException {
        if (input == null || input.isBlank()) {
            return Map.of("match_all", Map.of());
        }
        KqlParser parser = new KqlParser(input);
        Map<String, Object> query = parser.parseOr(null);
        parser.expect(Type.END, "Unexpected token");
        return query;
    }

    private Map<String, Object> parseOr(String field) throws SQLException {
        List<Map<String, Object>> clauses = new ArrayList<>();
        clauses.add(parseAnd(field));
        while (match(Type.OR)) clauses.add(parseAnd(field));
        return clauses.size() == 1
                ? clauses.get(0)
                : Map.of("bool", Map.of("should", clauses, "minimum_should_match", 1));
    }

    private Map<String, Object> parseAnd(String field) throws SQLException {
        List<Map<String, Object>> clauses = new ArrayList<>();
        clauses.add(parseNot(field));
        while (match(Type.AND) || startsExpression(peek().type)) {
            clauses.add(parseNot(field));
        }
        return clauses.size() == 1
                ? clauses.get(0)
                : Map.of("bool", Map.of("must", clauses));
    }

    private Map<String, Object> parseNot(String field) throws SQLException {
        if (match(Type.NOT)) {
            return Map.of("bool", Map.of("must_not", List.of(parseNot(field))));
        }
        return parsePrimary(field);
    }

    private Map<String, Object> parsePrimary(String inheritedField) throws SQLException {
        if (match(Type.LPAREN)) {
            Map<String, Object> query = parseOr(inheritedField);
            expect(Type.RPAREN, "Expected )");
            return query;
        }

        Token first = expectValue("Expected a KQL field or value");
        if (match(Type.COLON)) {
            String field = qualify(inheritedField, first.text);
            if (match(Type.LBRACE)) {
                Map<String, Object> nestedQuery = parseOr(field + ".");
                expect(Type.RBRACE, "Expected }");
                return Map.of("nested", Map.of("path", field, "query", nestedQuery));
            }
            if (match(Type.LPAREN)) {
                Map<String, Object> query = parseOr(field);
                expect(Type.RPAREN, "Expected )");
                return query;
            }
            return valueQuery(field, expectValue("Expected a value after :"));
        }

        Type comparison = peek().type;
        if (comparison == Type.GT || comparison == Type.GTE
                || comparison == Type.LT || comparison == Type.LTE) {
            cursor++;
            Token value = expectValue("Expected a value after comparison operator");
            return rangeQuery(qualify(inheritedField, first.text), comparison, value.text);
        }

        return valueQuery(inheritedField, first);
    }

    private static Map<String, Object> valueQuery(String field, Token value) {
        if (field == null) {
            if (containsWildcard(value.text) && !value.quoted) {
                return Map.of("query_string", Map.of(
                        "query", value.text,
                        "fields", List.of("*"),
                        "analyze_wildcard", true));
            }
            return Map.of("multi_match", Map.of(
                    "query", value.text,
                    "fields", List.of("*"),
                    "type", value.quoted ? "phrase" : "best_fields"));
        }
        if ("*".equals(value.text) && !value.quoted) {
            return Map.of("exists", Map.of("field", field));
        }
        if (containsWildcard(value.text) && !value.quoted) {
            return Map.of("wildcard", Map.of(field, Map.of(
                    "value", value.text,
                    "case_insensitive", true)));
        }
        String queryType = value.quoted ? "match_phrase" : "match";
        return Map.of(queryType, Map.of(field, value.text));
    }

    private static Map<String, Object> rangeQuery(String field, Type operator, String value) {
        String key = switch (operator) {
            case GT -> "gt";
            case GTE -> "gte";
            case LT -> "lt";
            case LTE -> "lte";
            default -> throw new IllegalArgumentException("Not a range operator");
        };
        return Map.of("range", Map.of(field, Map.of(key, scalar(value))));
    }

    private static Object scalar(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        try {
            if (value.matches("-?\\d+")) return Long.parseLong(value);
            if (value.matches("-?(?:\\d+\\.\\d*|\\d*\\.\\d+)")) return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            // Elasticsearch can still interpret the original value.
        }
        return value;
    }

    private static boolean containsWildcard(String value) {
        return value.indexOf('*') >= 0;
    }

    private static String qualify(String inheritedField, String field) {
        return inheritedField != null && inheritedField.endsWith(".")
                ? inheritedField + field
                : field;
    }

    private boolean startsExpression(Type type) {
        return type == Type.VALUE || type == Type.STRING || type == Type.LPAREN || type == Type.NOT;
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private boolean match(Type type) {
        if (peek().type != type) return false;
        cursor++;
        return true;
    }

    private Token expect(Type type, String message) throws SQLException {
        Token token = peek();
        if (token.type != type) throw syntax(message, token.position);
        cursor++;
        return token;
    }

    private Token expectValue(String message) throws SQLException {
        Token token = peek();
        if (token.type != Type.VALUE && token.type != Type.STRING) {
            throw syntax(message, token.position);
        }
        cursor++;
        return token;
    }

    private static List<Token> tokenize(String input) throws SQLException {
        List<Token> result = new ArrayList<>();
        int position = 0;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (Character.isWhitespace(c)) {
                position++;
                continue;
            }
            Type punctuation = switch (c) {
                case '(' -> Type.LPAREN;
                case ')' -> Type.RPAREN;
                case '{' -> Type.LBRACE;
                case '}' -> Type.RBRACE;
                case ':' -> Type.COLON;
                default -> null;
            };
            if (punctuation != null) {
                result.add(new Token(punctuation, Character.toString(c), false, position++));
                continue;
            }
            if (c == '>' || c == '<') {
                int start = position++;
                boolean equals = position < input.length() && input.charAt(position) == '=';
                if (equals) position++;
                Type type = c == '>'
                        ? equals ? Type.GTE : Type.GT
                        : equals ? Type.LTE : Type.LT;
                result.add(new Token(type, input.substring(start, position), false, start));
                continue;
            }
            if (c == '"') {
                int start = position++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (position < input.length()) {
                    c = input.charAt(position++);
                    if (c == '\\' && position < input.length()) {
                        value.append(input.charAt(position++));
                    } else if (c == '"') {
                        closed = true;
                        break;
                    } else {
                        value.append(c);
                    }
                }
                if (!closed) throw syntax("Unterminated quoted KQL value", start);
                result.add(new Token(Type.STRING, value.toString(), true, start));
                continue;
            }

            int start = position;
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                c = input.charAt(position);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '{'
                        || c == '}' || c == ':'
                        || c == '<' || c == '>') break;
                if (c == '\\' && position + 1 < input.length()) {
                    value.append(input.charAt(position + 1));
                    position += 2;
                } else {
                    value.append(c);
                    position++;
                }
            }
            if (value.isEmpty()) throw syntax("Unexpected KQL character", start);
            String text = value.toString();
            Type type = switch (text.toUpperCase(Locale.ROOT)) {
                case "AND" -> Type.AND;
                case "OR" -> Type.OR;
                case "NOT" -> Type.NOT;
                default -> Type.VALUE;
            };
            result.add(new Token(type, text, false, start));
        }
        result.add(new Token(Type.END, "", false, input.length()));
        return result;
    }

    private static SQLException syntax(String message, int position) {
        return new SQLException(message + " at KQL position " + position, "42000");
    }

    private enum Type {
        VALUE, STRING, LPAREN, RPAREN, LBRACE, RBRACE, COLON,
        GT, GTE, LT, LTE, AND, OR, NOT, END
    }

    private record Token(Type type, String text, boolean quoted, int position) {}
}
