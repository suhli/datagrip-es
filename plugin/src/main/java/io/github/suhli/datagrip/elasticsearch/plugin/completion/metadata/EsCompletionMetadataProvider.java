package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import java.util.List;

/** Backend used by {@link EsCompletionMetadataService} to refresh snapshots off the EDT. */
public interface EsCompletionMetadataProvider {
    String datasourceId();

    String esVersion();

    List<EsCompletionMetadataSnapshot.IndexObject> listTargets() throws Exception;

    /**
     * Returns flattened fields for the given concrete index names.
     * Implementations must batch mapping fetches.
     */
    List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) throws Exception;
}
