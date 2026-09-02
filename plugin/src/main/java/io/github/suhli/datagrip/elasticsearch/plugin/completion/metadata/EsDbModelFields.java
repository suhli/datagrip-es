package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.diagnostic.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads flattened mapping fields from an already-introspected DataGrip datasource model. */
public final class EsDbModelFields {
    private static final Logger LOG = Logger.getInstance(EsDbModelFields.class);

    private EsDbModelFields() {}

    public static Map<String, EsCompletionMetadataSnapshot.FieldInfo> load(
            DbDataSource dataSource, List<String> indexNames) {
        if (dataSource == null || indexNames == null || indexNames.isEmpty()) {
            return Map.of();
        }
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields = new LinkedHashMap<>();
        for (String indexName : indexNames) {
            if (indexName == null || indexName.isBlank()) continue;
            collectIndexFields(dataSource, indexName, fields);
        }
        return fields;
    }

    private static void collectIndexFields(
            DbDataSource dataSource,
            String indexName,
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields) {
        try {
            for (DasObject object : dataSource.getNameIndex().getObjectsByName(indexName)) {
                if (!(object instanceof DasTable table) || table.isSystem()) continue;
                for (DasColumn column : DasUtil.getColumns(table)) {
                    String path = column.getName();
                    if (path == null || path.isBlank()) continue;
                    String type = readEsType(column);
                    boolean multiField = path.contains(".") && !path.endsWith(".keyword");
                    fields.merge(
                            path,
                            new EsCompletionMetadataSnapshot.FieldInfo(
                                    path, Set.of(type), Set.of(indexName), multiField),
                            EsDbModelFields::mergeField);
                }
            }
        } catch (Throwable e) {
            LOG.debug("Cannot read Elasticsearch fields from datasource model for " + indexName, e);
        }
    }

    private static String readEsType(DasColumn column) {
        String comment = column.getComment();
        if (comment != null && comment.startsWith("Elasticsearch type: ")) {
            return comment.substring("Elasticsearch type: ".length()).trim();
        }
        return "object";
    }

    private static EsCompletionMetadataSnapshot.FieldInfo mergeField(
            EsCompletionMetadataSnapshot.FieldInfo left,
            EsCompletionMetadataSnapshot.FieldInfo right) {
        Set<String> types = new LinkedHashSet<>(left.types());
        types.addAll(right.types());
        Set<String> coverage = new LinkedHashSet<>(left.indexCoverage());
        coverage.addAll(right.indexCoverage());
        return new EsCompletionMetadataSnapshot.FieldInfo(
                left.path(), types, coverage, left.multiField() || right.multiField());
    }
}
