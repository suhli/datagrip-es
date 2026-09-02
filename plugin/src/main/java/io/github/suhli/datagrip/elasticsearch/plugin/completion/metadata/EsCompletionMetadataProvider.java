package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import java.util.List;

/** Backend used by {@link EsCompletionMetadataService} to refresh snapshots off the EDT. */
public interface EsCompletionMetadataProvider extends AutoCloseable {
    String datasourceId();

    String esVersion();

    /** Refreshes datasource-level cluster metadata such as the server version. */
    default void refreshClusterMetadata() throws Exception {}

    List<EsCompletionMetadataSnapshot.IndexObject> listTargets() throws Exception;

    /**
     * Returns flattened fields for the given concrete index names.
     * Implementations must batch mapping fetches.
     */
    List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) throws Exception;

    @Override
    default void close() throws Exception {}
}
