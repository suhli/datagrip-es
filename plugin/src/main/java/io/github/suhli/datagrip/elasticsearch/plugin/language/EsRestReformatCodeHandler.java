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
import com.intellij.psi.PsiFile;

import org.jetbrains.annotations.NotNull;

/** Routes database-console reformat to the Elasticsearch REST formatter. */
public final class EsRestReformatCodeHandler extends EditorActionHandler {
    private final EditorActionHandler delegate;

    public EsRestReformatCodeHandler(@NotNull EditorActionHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    protected boolean isEnabledForCaret(
            @NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        if (isEsRestEditor(editor)) return true;
        return delegate.isEnabled(editor, caret, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        if (!isEsRestEditor(editor)) {
            delegate.execute(editor, caret, dataContext);
            return;
        }
        Project project = editor.getProject();
        if (project == null) return;

        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitAllDocuments();
        Document document = editor.getDocument();
        PsiFile file = documentManager.getPsiFile(document);
        if (file == null || file.getLanguage() != EsRestLanguage.INSTANCE) {
            delegate.execute(editor, caret, dataContext);
            return;
        }

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

    private static boolean isEsRestEditor(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return false;
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        return file != null && file.getLanguage() == EsRestLanguage.INSTANCE;
    }
}
