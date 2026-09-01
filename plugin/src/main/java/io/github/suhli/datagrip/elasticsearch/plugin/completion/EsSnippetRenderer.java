package io.github.suhli.datagrip.elasticsearch.plugin.completion;

/**
 * Expands schema snippet placeholders without Live Templates.
 * Avoids unresolved {@code $FIELD$}/{@code $END$} artifacts in the editor.
 */
public final class EsSnippetRenderer {
    public record RenderResult(String text, int cursorOffset) {}

    private EsSnippetRenderer() {}

    public static RenderResult render(String template) {
        if (template == null || template.isBlank()) {
            return new RenderResult("", -1);
        }
        String result = template;
        int cursor = -1;

        int quotedField = result.indexOf("\"$FIELD$\"");
        if (quotedField >= 0) {
            result = result.replace("\"$FIELD$\"", "\"\"");
            cursor = quotedField + 1;
        }
        result = result.replace("\"$VALUE$\"", "\"\"");
        result = result.replace("\"$INTERVAL$\"", "\"\"");

        int endMarker = result.indexOf("$END$");
        if (endMarker >= 0) {
            result = result.replace("$END$", "");
            if (cursor < 0) {
                cursor = endMarker;
            }
        }

        int bareField = result.indexOf("$FIELD$");
        if (bareField >= 0) {
            result = result.replace("$FIELD$", "");
            if (cursor < 0) {
                cursor = bareField;
            }
        }
        result = result.replace("$VALUE$", "");
        result = result.replace("$INTERVAL$", "");

        return new RenderResult(result, cursor);
    }

    /** Places the caret inside the empty key quotes of a {@code "": ""} field-object pair. */
    public static int findEmptyFieldKeyCursor(CharSequence text, int nearOffset) {
        int from = Math.max(0, nearOffset - 40);
        int to = Math.min(text.length(), nearOffset + 160);
        for (int i = from; i < to - 3; i++) {
            if (text.charAt(i) != '"' || text.charAt(i + 1) != '"') {
                continue;
            }
            int j = i + 2;
            while (j < to && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j < to && text.charAt(j) == ':') {
                return i + 1;
            }
        }
        return -1;
    }
}
