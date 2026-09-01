package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.EditorHighlighterProvider;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Ensures ES REST consoles use the dialect lexer for editor tokenization and brace matching. */
public final class EsRestEditorHighlighterProvider implements EditorHighlighterProvider {
    @Override
    public @NotNull EditorHighlighter getEditorHighlighter(
            @Nullable Project project,
            @NotNull FileType fileType,
            @Nullable VirtualFile virtualFile,
            @NotNull EditorColorsScheme colors) {
        return new LexerEditorHighlighter(new EsRestSyntaxHighlighter(), colors);
    }
}
