package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Pretty-prints pasted JSON object/array fragments in Elasticsearch REST consoles. */
public final class EsRestCopyPastePreProcessor implements CopyPastePreProcessor {
    @Override
    public @Nullable String preprocessOnCopy(
            PsiFile file, int[] startOffsets, int[] endOffsets, String text) {
        return null;
    }

    @Override
    public @NotNull String preprocessOnPaste(
            Project project, PsiFile file, Editor editor, String text, RawText rawText) {
        if (!EsRestFileDetector.isEsRestFile(file) || text == null || text.isBlank()) {
            return text;
        }
        String formatted = formatPastedJson(text);
        return formatted == null ? text : formatted;
    }

    static @Nullable String formatPastedJson(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        String formatted = EsRestDocumentFormatter.format(trimmed);
        return formatted.equals(trimmed) ? null : formatted;
    }
}
