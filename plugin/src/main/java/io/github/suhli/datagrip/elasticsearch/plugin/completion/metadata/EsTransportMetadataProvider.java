package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import io.github.suhli.datagrip.elasticsearch.EsClusterMetadata;
import io.github.suhli.datagrip.elasticsearch.MappingFlattener;
import io.github.suhli.datagrip.elasticsearch.Transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;

/** Transport-backed metadata provider shared with JDBC semantics. */
public final class EsTransportMetadataProvider implements EsCompletionMetadataProvider {
    private final String datasourceId;
    private final String esVersion;
    private final EsClusterMetadata metadata;

    public EsTransportMetadataProvider(
            String datasourceId, String esVersion, Transport transport, URI endpoint) {
        this.datasourceId = datasourceId;
        this.esVersion = esVersion == null ? "" : esVersion;
        this.metadata = new EsClusterMetadata(transport, endpoint);
    }

    @Override
    public String datasourceId() {
        return datasourceId;
    }

    @Override
    public String esVersion() {
        return esVersion;
    }

    @Override
    public List<EsCompletionMetadataSnapshot.IndexObject> listTargets() throws Exception {
        Map<String, EsCompletionMetadataSnapshot.IndexObject> unique = new LinkedHashMap<>();
        for (EsClusterMetadata.IndexInfo index : metadata.indices()) {
            unique.put(index.name(), new EsCompletionMetadataSnapshot.IndexObject(index.name(), "index"));
        }
        for (EsClusterMetadata.NamedObject alias : metadata.aliases()) {
            unique.put(alias.name(), new EsCompletionMetadataSnapshot.IndexObject(alias.name(), "alias"));
        }
        for (EsClusterMetadata.NamedObject stream : metadata.dataStreams()) {
            unique.put(stream.name(), new EsCompletionMetadataSnapshot.IndexObject(stream.name(), "data_stream"));
        }
        return List.copyOf(unique.values());
    }

    @Override
    public List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) throws Exception {
        Map<String, JsonNode> mappings = metadata.mappings(indexNames);
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> merged = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : mappings.entrySet()) {
            for (MappingFlattener.Field field : MappingFlattener.flatten(entry.getValue())) {
                merged.merge(
                        field.name(),
                        new EsCompletionMetadataSnapshot.FieldInfo(
                                field.name(),
                                Set.of(field.esType()),
                                Set.of(entry.getKey()),
                                field.multiField()),
                        (left, right) -> {
                            Set<String> types = new LinkedHashSet<>(left.types());
                            types.addAll(right.types());
                            Set<String> coverage = new LinkedHashSet<>(left.indexCoverage());
                            coverage.addAll(right.indexCoverage());
                            return new EsCompletionMetadataSnapshot.FieldInfo(
                                    left.path(), types, coverage, left.multiField() || right.multiField());
                        });
            }
        }
        return new ArrayList<>(merged.values());
    }
}
