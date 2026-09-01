package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.formatting.Block;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import com.intellij.formatting.Spacing;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Applies JSON-only pretty printing during CodeStyle reformat (Ctrl+Alt+L) and then
 * returns a no-op formatting model so SQL/PSI formatters do not add block indentation.
 */
public final class EsRestFormattingModelBuilder implements FormattingModelBuilder {
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        PsiFile file = context.getContainingFile();
        if (EsRestFileDetector.isEsRestFile(file)) {
            applyJsonFormatting(context, file);
        }
        CodeStyleSettings settings = context.getCodeStyleSettings();
        return FormattingModelProvider.createFormattingModelForPsiFile(
                file,
                new NoopBlock(file.getNode()),
                settings);
    }

    private static void applyJsonFormatting(@NotNull FormattingContext context, @NotNull PsiFile file) {
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) return;
        TextRange range = context.getFormattingRange();
        if (range == null) {
            range = TextRange.create(0, document.getTextLength());
        }
        String original = document.getText(range);
        String formatted = EsRestDocumentFormatter.format(original);
        if (formatted.equals(original)) return;
        document.replaceString(range.getStartOffset(), range.getEndOffset(), formatted);
    }

    private static final class NoopBlock extends AbstractBlock {
        NoopBlock(ASTNode node) {
            super(node, null, null);
        }

        @Override
        protected @NotNull List<Block> buildChildren() {
            return List.of();
        }

        @Override
        public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
            return null;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }
    }
}
