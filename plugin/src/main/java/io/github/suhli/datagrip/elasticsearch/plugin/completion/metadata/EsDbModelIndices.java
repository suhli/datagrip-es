package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.psi.DbDataSource;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads index names from an already-introspected DataGrip datasource model (no network I/O). */
public final class EsDbModelIndices {
    private static final Logger LOG = Logger.getInstance(EsDbModelIndices.class);

    private EsDbModelIndices() {}

    public static List<EsCompletionMetadataSnapshot.IndexObject> list(DbDataSource dataSource) {
        if (dataSource == null) return List.of();
        try {
            Map<String, EsCompletionMetadataSnapshot.IndexObject> unique = new LinkedHashMap<>();
            for (String name : dataSource.getNameIndex().getAllNames()) {
                if (name == null || name.isBlank()) continue;
                for (DasObject obj : dataSource.getNameIndex().getObjectsByName(name)) {
                    if (!(obj instanceof DasTable table) || table.isSystem()) continue;
                    unique.putIfAbsent(name, new EsCompletionMetadataSnapshot.IndexObject(name, "index"));
                }
            }
            return List.copyOf(unique.values());
        } catch (Throwable e) {
            LOG.debug("Cannot read Elasticsearch indices from datasource model", e);
            return List.of();
        }
    }
}
