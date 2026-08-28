package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.database.script.ScriptModelUtilCore;
import com.intellij.database.util.SearchPath;
import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.sql.dialects.base.EvaluationHelperBase;
import com.intellij.sql.psi.SqlTableType;
import com.intellij.util.containers.JBIterable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EsRestEvaluationHelper extends EvaluationHelperBase {
    @Override
    public <V> @NotNull Condition<V> isStatement(@NotNull SyntaxTraverser.Api<V> api) {
        return Conditions.compose(api.TO_TYPE, Conditions.equalTo(EsRestTypes.REQUEST));
    }

    @Override
    public <V> @NotNull Condition<V> isFile(@NotNull SyntaxTraverser.Api<V> api) {
        return Conditions.compose(api.TO_TYPE, Conditions.equalTo(EsRestTypes.FILE));
    }

    @Override
    public <V> @NotNull Condition<V> isBatchBlock(@NotNull SyntaxTraverser.Api<V> api) {
        return isFile(api);
    }

    @Override
    public <V> @NotNull Condition<V> isWsOrComment(@NotNull SyntaxTraverser.Api<V> api) {
        return node -> {
            Object type = api.TO_TYPE.fun(node);
            return type == TokenType.WHITE_SPACE || type == EsRestTypes.COMMENT;
        };
    }

    @Override
    public <V> @NotNull Condition<V> isStatementSeparator(
            @NotNull SyntaxTraverser.Api<V> api, @NotNull Language language) {
        return node -> {
            if (api.TO_TYPE.fun(node) != TokenType.WHITE_SPACE) return false;
            if (!(node instanceof PsiElement element)) return false;
            String text = element.getText();
            return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        };
    }

    @Override
    public <V> @NotNull Condition<V> canContainStatements(@NotNull SyntaxTraverser.Api<V> api) {
        return Conditions.or(isFile(api), isBatchBlock(api));
    }

    @Override
    public <V> @NotNull SyntaxTraverser<V> statements(
            @Nullable TextRange range, @NotNull Language language, @NotNull SyntaxTraverser<V> traverser) {
        if (range == null) return traverser;

        Condition<V> executable = Conditions.or(
                isStatement(traverser.api),
                ScriptModelUtilCore.wholeFileCondition(
                        traverser,
                        range,
                        isWsOrComment(traverser.api),
                        isStatementSeparator(traverser.api, language),
                        isStatement(traverser.api),
                        isBatchBlock(traverser.api)));

        return ScriptModelUtilCore.inRange(
                traverser.reset().expand(canContainStatements(traverser.api)).filter(executable), range);
    }

    @Override
    public <V> @NotNull JBIterable<V> parameters(
            @Nullable TextRange range,
            @NotNull Language language,
            @NotNull SyntaxTraverser<V> traverser) {
        return JBIterable.empty();
    }

    @Override
    public <V> @NotNull JBIterable<V> externals(
            @Nullable TextRange range,
            @NotNull Language language,
            @NotNull SyntaxTraverser<V> traverser) {
        return JBIterable.empty();
    }

    @Override
    protected <V> @NotNull SyntaxTraverser<V> parse(
            @NotNull Project project,
            @NotNull Language language,
            @NotNull CharSequence documentText,
            @Nullable Language expected) {
        PsiFile file = PsiFileFactory.getInstance(project)
                .createFileFromText("console.esrest", EsRestFileType.INSTANCE, documentText);
        return (SyntaxTraverser<V>) SyntaxTraverser.psiTraverser(file);
    }

    @Override
    protected @NotNull Condition<IElementType> typesCondition() {
        return Conditions.alwaysTrue();
    }
 
    @Override
    public @Nullable SqlTableType parseQueryTableType(
            @NotNull Project project,
            @NotNull Language language,
            @Nullable com.intellij.database.psi.DbDataSource dataSource,
            @Nullable SearchPath searchPath,
            @NotNull String queryText,
            @Nullable Language expected) {
        return null;
    }

    @Override
    public @Nullable PsiElement parseQueryResultSetExpression(
            @NotNull Project project,
            @NotNull Language language,
            @NotNull String queryText,
            @Nullable Language expected) {
        return null;
    }
}
