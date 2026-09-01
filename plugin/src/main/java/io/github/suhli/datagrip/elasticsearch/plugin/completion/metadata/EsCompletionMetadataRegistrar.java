package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import io.github.suhli.datagrip.elasticsearch.EsJdbcUrl;
import io.github.suhli.datagrip.elasticsearch.HttpTransport;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestLanguage;

import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.psi.DbDataSource;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Registers transport-backed metadata providers for Elasticsearch datasources. */
public final class EsCompletionMetadataRegistrar {
    private static final Logger LOG = Logger.getInstance(EsCompletionMetadataRegistrar.class);
    private static final ConcurrentHashMap<String, Boolean> REGISTERED = new ConcurrentHashMap<>();

    private EsCompletionMetadataRegistrar() {}

    public static void ensureRegistered(Project project, DbDataSource dataSource) {
        if (project == null || dataSource == null || !isElasticsearch(dataSource)) return;
        String id = dataSource.getName();
        if (id == null || id.isBlank()) return;
        if (REGISTERED.putIfAbsent(id, Boolean.TRUE) != null) return;

        EsCompletionMetadataService service = EsCompletionMetadataService.getInstance(project);
        if (service.hasProvider(id)) return;

        try {
            RawDataSource raw = dataSource.getDelegate();
            if (!(raw instanceof LocalDataSource local)) return;
            String url = local.getUrl();
            if (url == null || !url.startsWith(EsJdbcUrl.PREFIX)) return;
            Properties props = new Properties();
            EsJdbcUrl config = EsJdbcUrl.parse(url, props);
            HttpTransport transport = new HttpTransport(config);
            service.registerProvider(new EsTransportMetadataProvider(id, "", transport, config.endpoint()));
        } catch (Throwable e) {
            REGISTERED.remove(id);
            LOG.debug("Cannot register Elasticsearch completion metadata provider", e);
        }
    }

    public static boolean isElasticsearch(DbDataSource dataSource) {
        try {
            if (EsRestLanguage.INSTANCE.equals(dataSource.getQueryLanguage())) return true;
            RawDataSource raw = dataSource.getDelegate();
            if (raw instanceof LocalDataSource local) {
                String url = local.getUrl();
                return url != null && url.startsWith(EsJdbcUrl.PREFIX);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }
}
