package io.github.suhli.datagrip.elasticsearch.plugin.completion;

/** Default structural snippets derived from schema value types. */
public final class EsSnippetDefaults {
    private EsSnippetDefaults() {}

    public static String bodyKeySnippet(String key, String valueType) {
        String escaped = escapeJsonKey(key);
        return switch (valueType == null ? "" : valueType) {
            case "query_array" -> "\"" + escaped + "\": [\n  {\n    $END$\n  }\n]";
            case "object", "field_object" -> "\"" + escaped + "\": {\n  $END$\n}";
            case "field" -> "\"" + escaped + "\": \"$FIELD$\"";
            case "string" -> "\"" + escaped + "\": \"$VALUE$\"";
            case "number" -> "\"" + escaped + "\": $VALUE$";
            case "boolean" -> "\"" + escaped + "\": $VALUE$";
            case "enum" -> "\"" + escaped + "\": \"$VALUE$\"";
            default -> "\"" + escaped + "\": $END$";
        };
    }

    public static String adjustForInsideString(String snippet) {
        if (snippet == null || snippet.isBlank()) return snippet;
        if (!snippet.startsWith("\"")) return snippet;
        int second = snippet.indexOf('"', 1);
        if (second > 1 && second + 1 < snippet.length() && snippet.charAt(second + 1) == ':') {
            return snippet.substring(1, second) + snippet.substring(second + 1);
        }
        return snippet;
    }

    private static String escapeJsonKey(String key) {
        return key.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
