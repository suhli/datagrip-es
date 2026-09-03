package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable metadata snapshot consumed by completion on the EDT. */
public final class EsCompletionMetadataSnapshot {
    public static final EsCompletionMetadataSnapshot EMPTY =
            new EsCompletionMetadataSnapshot("", "", List.of(), Map.of(), 0L);

    private final String datasourceId;
    private final String esVersion;
    private final List<IndexObject> indices;
    private final Map<String, FieldInfo> fields;
    private final long loadedAtMillis;
    private final boolean fieldsPartial;
    private final int matchedTargetCount;
    private final int loadedTargetCount;

    public EsCompletionMetadataSnapshot(
            String datasourceId,
            String esVersion,
            List<IndexObject> indices,
            Map<String, FieldInfo> fields,
            long loadedAtMillis) {
        this(datasourceId, esVersion, indices, fields, loadedAtMillis, false, 0, 0);
    }

    public EsCompletionMetadataSnapshot(
            String datasourceId,
            String esVersion,
            List<IndexObject> indices,
            Map<String, FieldInfo> fields,
            long loadedAtMillis,
            boolean fieldsPartial,
            int matchedTargetCount,
            int loadedTargetCount) {
        this.datasourceId = datasourceId == null ? "" : datasourceId;
        this.esVersion = esVersion == null ? "" : esVersion;
        this.indices = List.copyOf(indices);
        this.fields = Map.copyOf(fields);
        this.loadedAtMillis = loadedAtMillis;
        this.fieldsPartial = fieldsPartial;
        this.matchedTargetCount = matchedTargetCount;
        this.loadedTargetCount = loadedTargetCount;
    }

    public String datasourceId() { return datasourceId; }
    public String esVersion() { return esVersion; }
    public List<IndexObject> indices() { return indices; }
    public Map<String, FieldInfo> fields() { return fields; }
    public long loadedAtMillis() { return loadedAtMillis; }
    public boolean fieldsPartial() { return fieldsPartial; }
    public int matchedTargetCount() { return matchedTargetCount; }
    public int loadedTargetCount() { return loadedTargetCount; }

    public boolean isExpired(long nowMillis, long ttlMillis) {
        return loadedAtMillis <= 0 || nowMillis - loadedAtMillis > ttlMillis;
    }

    public record IndexObject(String name, String kind) {}

    public record FieldInfo(
            String path,
            Set<String> types,
            Set<String> indexCoverage,
            boolean multiField) {
        public FieldInfo {
            types = Set.copyOf(types);
            indexCoverage = Set.copyOf(indexCoverage);
        }

        public String primaryType() {
            return types.isEmpty() ? "object" : types.iterator().next();
        }
    }
}
