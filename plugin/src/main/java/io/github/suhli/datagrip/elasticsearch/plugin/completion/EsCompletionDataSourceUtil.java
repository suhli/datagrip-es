package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.database.psi.DbDataSource;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

/** Best-effort datasource/version resolution. Never performs network I/O. */
public final class EsCompletionDataSourceUtil {
    private EsCompletionDataSourceUtil() {}

    public static String resolveDatasourceId(CompletionParameters parameters) {
        DbDataSource dataSource = findDataSource(parameters);
        if (dataSource == null) return "";
        try {
            return dataSource.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String resolveVersion(CompletionParameters parameters) {
        return "";
    }

    private static @Nullable DbDataSource findDataSource(CompletionParameters parameters) {
        PsiFile file = parameters.getOriginalFile();
        Object maybe = file.getUserData(com.intellij.openapi.util.Key.create("EsRest.DbDataSource"));
        if (maybe instanceof DbDataSource db) return db;
        return null;
    }
}
