package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor;

import org.jetbrains.annotations.NotNull;

/**
 * Fallback for SQL-dialect console files: pretty-print JSON before any SQL formatter runs.
 */
public final class EsRestPreFormatProcessor implements PreFormatProcessor {
    @Override
    public @NotNull TextRange process(@NotNull ASTNode element, @NotNull TextRange range) {
        PsiElement psi = element.getPsi();
        PsiFile file = psi instanceof PsiFile f ? f : psi == null ? null : psi.getContainingFile();
        if (!EsRestFileDetector.isEsRestFile(file)) return range;
        if (file != null && element != file.getNode()) return range;

        Document document = file == null ? null
                : PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) return range;

        String original = document.getText(range);
        String formatted = EsRestDocumentFormatter.format(original);
        if (formatted.equals(original)) return range;

        document.replaceString(range.getStartOffset(), range.getEndOffset(), formatted);
        return TextRange.create(range.getStartOffset(), range.getStartOffset() + formatted.length());
    }

    @Override
    public boolean changesWhitespacesOnly() {
        return false;
    }
}
