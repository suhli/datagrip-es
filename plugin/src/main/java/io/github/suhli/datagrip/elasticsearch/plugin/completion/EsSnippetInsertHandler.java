package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

/**
 * Context-aware JSON/URL insert handler with optional Live Template tab stops.
 */
public final class EsSnippetInsertHandler implements InsertHandler<LookupElement> {
    private final String insertion;
    private final boolean alreadyQuoted;
    private final boolean asJsonKey;
    private final @Nullable String templateText;
    private final int cursorOffsetFromEnd;
    private final boolean replaceEmptyFieldKeyPair;

    public EsSnippetInsertHandler(String insertion, boolean alreadyQuoted, boolean asJsonKey) {
        this(insertion, alreadyQuoted, asJsonKey, null, 0, false);
    }

    public EsSnippetInsertHandler(
            String insertion, boolean alreadyQuoted, boolean asJsonKey, @Nullable String templateText) {
        this(insertion, alreadyQuoted, asJsonKey, templateText, 0, false);
    }

    private EsSnippetInsertHandler(
            String insertion,
            boolean alreadyQuoted,
            boolean asJsonKey,
            @Nullable String templateText,
            int cursorOffsetFromEnd,
            boolean replaceEmptyFieldKeyPair) {
        this.insertion = insertion;
        this.alreadyQuoted = alreadyQuoted;
        this.asJsonKey = asJsonKey;
        this.templateText = templateText;
        this.cursorOffsetFromEnd = cursorOffsetFromEnd;
        this.replaceEmptyFieldKeyPair = replaceEmptyFieldKeyPair;
    }

    /** Inserts a mapping field as a JSON object key inside an open string (e.g. term's {@code ""}). */
    static EsSnippetInsertHandler forFieldKeyInsideString(String fieldPath) {
        return new EsSnippetInsertHandler(fieldPath, true, false, null, 0, false);
    }

    /**
     * Inserts {@code "field": ""} when the caret is after {@code term: \{} and offers mapping keys.
     * Cursor lands inside the value quotes.
     */
    static EsSnippetInsertHandler forFieldKeyPair(String fieldPath) {
        String text = "\"" + escape(fieldPath) + "\": \"\"";
        return new EsSnippetInsertHandler(text, false, false, null, 1, false);
    }

    /** Replaces {@code "": ""} with {@code "field": ""} when completing an empty field-object key placeholder. */
    static EsSnippetInsertHandler forEmptyFieldKeyPair(String fieldPath) {
        return new EsSnippetInsertHandler(fieldPath, false, false, null, 1, true);
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        Editor editor = context.getEditor();
        Document document = editor.getDocument();
        int start = context.getStartOffset();
        int tail = context.getTailOffset();

        if (replaceEmptyFieldKeyPair) {
            int keyStart = findEmptyFieldKeyPairStart(document.getCharsSequence(), tail);
            if (keyStart >= 0) {
                document.deleteString(keyStart, tail);
                String text = "\"" + escape(insertion) + "\": \"\"";
                document.insertString(keyStart, text);
                editor.getCaretModel().moveToOffset(keyStart + text.length() - cursorOffsetFromEnd);
                return;
            }
        }

        document.deleteString(start, tail);

        if (templateText != null && !templateText.isBlank()) {
            insertTemplate(context, start);
            return;
        }

        String text = buildPlainInsertion(document, start);
        document.insertString(start, text);
        int cursor = start + text.length() - cursorOffsetFromEnd;
        editor.getCaretModel().moveToOffset(Math.max(start, cursor));
    }

    private void insertTemplate(InsertionContext context, int start) {
        String rendered = templateText;
        if (asJsonKey && alreadyQuoted) {
            rendered = stripOuterQuotesForTemplate(rendered);
        }
        TemplateManager manager = TemplateManager.getInstance(context.getProject());
        Template template = manager.createTemplate("es-rest-snippet", "esrest", rendered);
        template.setToReformat(true);
        if (rendered.contains("$FIELD$")) {
            template.addVariable("FIELD", "", "", true);
        }
        if (rendered.contains("$VALUE$")) {
            template.addVariable("VALUE", "", "", true);
        }
        manager.startTemplate(context.getEditor(), template);
    }

    static int findEmptyFieldKeyPairStart(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && text.charAt(i - 1) != ':' && text.charAt(i - 1) != '"') {
            i--;
        }
        if (i > 0 && text.charAt(i - 1) == '"') {
            i--;
        }
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i == 0 || text.charAt(i - 1) != ':') {
            return -1;
        }
        i--;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i < 2 || text.charAt(i - 1) != '"' || text.charAt(i - 2) != '"') {
            return -1;
        }
        return i - 2;
    }

    private String buildPlainInsertion(Document document, int start) {
        if (!asJsonKey) {
            return insertion;
        }
        if (alreadyQuoted) {
            return insertion;
        }
        return "\"" + escape(insertion) + "\"";
    }

    static String formatJsonStringValue(String value, boolean alreadyQuoted) {
        if (alreadyQuoted) {
            return value;
        }
        if (value.startsWith("\"") || value.equals("true") || value.equals("false")
                || value.equals("null") || looksNumeric(value)) {
            return value;
        }
        return "\"" + escape(value) + "\"";
    }

    private static boolean looksNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')) {
                return false;
            }
        }
        return true;
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripOuterQuotesForTemplate(String template) {
        if (template.startsWith("\"") && template.contains("\":")) {
            int end = template.indexOf('"', 1);
            if (end > 1) {
                return template.substring(1, end) + template.substring(end + 1);
            }
        }
        return template;
    }

    public static boolean isInsideQuotes(Document document, int offset) {
        if (offset <= 0 || offset > document.getTextLength()) {
            return false;
        }
        int lineStart = document.getLineStartOffset(
                document.getLineNumber(Math.min(offset, document.getTextLength() - 1)));
        String line = document.getText(new TextRange(lineStart, offset));
        boolean in = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                in = !in;
            }
        }
        return in;
    }
}
