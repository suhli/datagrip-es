package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;

import org.jetbrains.annotations.NotNull;

/** Routes editor reformat shortcuts to JSON-only pretty printing. */
public final class EsRestReformatCodeHandler extends EditorActionHandler {
    private final EditorActionHandler delegate;

    public EsRestReformatCodeHandler(@NotNull EditorActionHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    protected boolean isEnabledForCaret(
            @NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        if (EsRestFileDetector.isEsRestDocument(editor.getProject(), editor.getDocument())) return true;
        return delegate.isEnabled(editor, caret, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        if (project == null || !EsRestFileDetector.isEsRestDocument(project, editor.getDocument())) {
            delegate.execute(editor, caret, dataContext);
            return;
        }

        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitAllDocuments();
        Document document = editor.getDocument();
        TextRange range = reformatRange(editor);
        String original = document.getText(range);
        String formatted = EsRestDocumentFormatter.format(original);
        if (formatted.equals(original)) return;

        WriteCommandAction.runWriteCommandAction(project, "Reformat Elasticsearch REST", null, () -> {
            document.replaceString(range.getStartOffset(), range.getEndOffset(), formatted);
            documentManager.commitDocument(document);
        });
    }

    private static TextRange reformatRange(@NotNull Editor editor) {
        if (editor.getSelectionModel().hasSelection()) {
            return new TextRange(
                    editor.getSelectionModel().getSelectionStart(),
                    editor.getSelectionModel().getSelectionEnd());
        }
        return new TextRange(0, editor.getDocument().getTextLength());
    }
}
