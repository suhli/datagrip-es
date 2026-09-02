package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.database.console.client.DatabaseSessionClientWithFile;
import com.intellij.database.console.session.DatabaseSession;
import com.intellij.database.console.session.DatabaseSessionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.util.DbUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataRegistrar;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Best-effort datasource/version resolution. Never performs network I/O. */
public final class EsCompletionDataSourceUtil {
    private static final Key<DbDataSource> DATASOURCE_KEY = Key.create("EsRest.DbDataSource");

    private EsCompletionDataSourceUtil() {}

    public static @Nullable DbDataSource findDataSource(CompletionParameters parameters) {
        PsiFile file = parameters.getOriginalFile();
        Object maybe = file.getUserData(DATASOURCE_KEY);
        if (maybe instanceof DbDataSource db) return db;

        Project project = file.getProject();
        VirtualFile virtualFile = file.getVirtualFile();
        if (project != null && virtualFile != null) {
            DbDataSource fromSession = findFromConsoleSession(project, virtualFile);
            if (fromSession != null) return fromSession;
        }

        if (project != null) {
            DbDataSource fallback = findSingleElasticsearchDataSource(project);
            if (fallback != null) return fallback;
        }
        return null;
    }

    public static String resolveDatasourceId(CompletionParameters parameters) {
        DbDataSource dataSource = findDataSource(parameters);
        if (dataSource == null) return "";
        try {
            return EsCompletionMetadataRegistrar.datasourceId(dataSource);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String resolveVersion(CompletionParameters parameters) {
        return "";
    }

    public static void bindDataSource(PsiFile file, DbDataSource dataSource) {
        if (file != null && dataSource != null) {
            file.putUserData(DATASOURCE_KEY, dataSource);
        }
    }

    private static @Nullable DbDataSource findFromConsoleSession(Project project, VirtualFile virtualFile) {
        try {
            for (DatabaseSession session : DatabaseSessionManager.getSessions(project)) {
                for (DatabaseSessionClientWithFile client : session.getClientsWithFile()) {
                    if (!virtualFile.equals(client.getVirtualFile())) continue;
                    LocalDataSource local = session.getConnectionPoint().getDataSource();
                    DbDataSource dataSource = DbPsiFacade.getInstance(project).findDataSource(local.getName());
                    if (dataSource != null) return dataSource;
                }
            }
        } catch (Throwable ignored) {
            // Database console APIs are not fully public; ignore and fall back.
        }
        return null;
    }

    private static @Nullable DbDataSource findSingleElasticsearchDataSource(Project project) {
        List<DbDataSource> matches = new ArrayList<>();
        for (DbDataSource dataSource : DbUtil.getDataSources(project)) {
            if (EsCompletionMetadataRegistrar.isElasticsearch(dataSource)) {
                matches.add(dataSource);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
