package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import io.github.suhli.datagrip.elasticsearch.EsJdbcUrl;
import io.github.suhli.datagrip.elasticsearch.HttpTransport;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestLanguage;

import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.psi.DbDataSource;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.Properties;

/** Registers transport-backed metadata providers for Elasticsearch datasources. */
public final class EsCompletionMetadataRegistrar {
    private static final Logger LOG = Logger.getInstance(EsCompletionMetadataRegistrar.class);

    private EsCompletionMetadataRegistrar() {}

    public static void ensureRegistered(Project project, DbDataSource dataSource) {
        if (project == null || dataSource == null || !isElasticsearch(dataSource)) return;
        String id = datasourceId(dataSource);
        if (id == null || id.isBlank()) return;

        try {
            String url = dataSource.getConnectionConfig().getUrl();
            if (url == null || !url.startsWith(EsJdbcUrl.PREFIX)) return;
            Properties props = effectiveConnectionProperties(project, dataSource);
            EsJdbcUrl config = EsJdbcUrl.parse(url, props);
            String fingerprint = fingerprint(url, config.properties());
            EsCompletionMetadataService service = EsCompletionMetadataService.getInstance(project);
            if (service.hasProvider(id, fingerprint)) return;
            HttpTransport transport = new HttpTransport(config);
            EsTransportMetadataProvider provider =
                    new EsTransportMetadataProvider(id, "", transport, config.endpoint());
            try {
                service.registerProvider(id, fingerprint, provider);
            } catch (Throwable e) {
                provider.close();
                throw e;
            }
        } catch (Throwable e) {
            LOG.debug("Cannot register Elasticsearch completion metadata provider", e);
        }
    }

    public static String datasourceId(DbDataSource dataSource) {
        String uniqueId = dataSource.getUniqueId();
        return uniqueId == null || uniqueId.isBlank() ? dataSource.getName() : uniqueId;
    }

    private static Properties effectiveConnectionProperties(Project project, DbDataSource dataSource) {
        Properties result = new Properties();
        LocalDataSource local = findLocalDataSource(project, dataSource);
        if (local == null) return result;
        result.putAll(local.getConnectionProperties());
        try {
            var credentials = DatabaseCredentials.getInstance().getCredentials(local);
            String username = credentials == null ? null : credentials.getUserName();
            if (username == null || username.isBlank()) username = local.getUsername();
            if (username != null && !username.isBlank()) result.setProperty("user", username);
            var password = credentials == null ? null : credentials.getPassword();
            if (password != null) result.setProperty("password", password.toString());
        } catch (Throwable ignored) {
            LOG.debug("Cannot read datasource credential for completion metadata");
            if (local.getUsername() != null && !local.getUsername().isBlank()) {
                result.setProperty("user", local.getUsername());
            }
        }
        return result;
    }

    private static LocalDataSource findLocalDataSource(Project project, DbDataSource dataSource) {
        String uniqueId = dataSource.getUniqueId();
        for (LocalDataSource candidate : LocalDataSourceManager.getInstance(project).getDataSources()) {
            if (uniqueId != null && uniqueId.equals(candidate.getUniqueId())) return candidate;
            if ((uniqueId == null || uniqueId.isBlank()) && dataSource.getName().equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private static String fingerprint(String url, Properties properties) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(url.getBytes(StandardCharsets.UTF_8));
        properties.stringPropertyNames().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(key -> {
                    digest.update((byte) 0);
                    digest.update(key.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '=');
                    String value = properties.getProperty(key, "");
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                });
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    public static boolean isElasticsearch(DbDataSource dataSource) {
        try {
            if (EsRestLanguage.INSTANCE.equals(dataSource.getQueryLanguage())) return true;
            String url = dataSource.getConnectionConfig().getUrl();
            return url != null && url.startsWith(EsJdbcUrl.PREFIX);
        } catch (Throwable ignored) {
            // ignore
        }
        return false;
    }
}
