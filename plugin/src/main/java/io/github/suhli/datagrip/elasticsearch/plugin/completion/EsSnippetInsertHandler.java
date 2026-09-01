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

    public EsSnippetInsertHandler(String insertion, boolean alreadyQuoted, boolean asJsonKey) {
        this(insertion, alreadyQuoted, asJsonKey, null);
    }

    public EsSnippetInsertHandler(
            String insertion, boolean alreadyQuoted, boolean asJsonKey, @Nullable String templateText) {
        this.insertion = insertion;
        this.alreadyQuoted = alreadyQuoted;
        this.asJsonKey = asJsonKey;
        this.templateText = templateText;
    }

    @Override
    public void handleInsert(InsertionContext context, LookupElement item) {
        Editor editor = context.getEditor();
        Document document = editor.getDocument();
        int start = context.getStartOffset();
        int tail = context.getTailOffset();

        // Remove the prefix that Completion already replaced inconsistently.
        document.deleteString(start, tail);

        if (templateText != null && !templateText.isBlank()) {
            insertTemplate(context, start);
            return;
        }

        String text = buildPlainInsertion(document, start);
        document.insertString(start, text);
        editor.getCaretModel().moveToOffset(start + text.length());
    }

    private void insertTemplate(InsertionContext context, int start) {
        String rendered = templateText;
        if (asJsonKey && alreadyQuoted) {
            rendered = stripOuterQuotesForTemplate(rendered);
        } else if (asJsonKey && !alreadyQuoted) {
            // keep quotes from template
        }
        TemplateManager manager = TemplateManager.getInstance(context.getProject());
        Template template = manager.createTemplate("es-rest-snippet", "esrest", rendered);
        template.setToReformat(true);
        manager.startTemplate(context.getEditor(), template);
    }

    private String buildPlainInsertion(Document document, int start) {
        if (!asJsonKey) {
            if (alreadyQuoted) return insertion;
            return insertion;
        }
        if (alreadyQuoted) return insertion;
        return "\"" + escape(insertion) + "\"";
    }

    static String formatJsonStringValue(String value, boolean alreadyQuoted) {
        if (alreadyQuoted) return value;
        if (value.startsWith("\"") || value.equals("true") || value.equals("false")
                || value.equals("null") || looksNumeric(value)) {
            return value;
        }
        return "\"" + escape(value) + "\"";
    }

    private static boolean looksNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripOuterQuotesForTemplate(String template) {
        // "\"term\": ..." when already inside quotes becomes term\": ... which is wrong.
        // Templates for keys should use $KEY$ without surrounding quotes when alreadyQuoted.
        if (template.startsWith("\"") && template.contains("\":")) {
            int end = template.indexOf('"', 1);
            if (end > 1) {
                return template.substring(1, end) + template.substring(end + 1);
            }
        }
        return template;
    }

    public static boolean isInsideQuotes(Document document, int offset) {
        if (offset <= 0 || offset > document.getTextLength()) return false;
        int lineStart = document.getLineStartOffset(document.getLineNumber(Math.min(offset, document.getTextLength() - 1)));
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
            if (c == '"') in = !in;
        }
        return in;
    }
}
