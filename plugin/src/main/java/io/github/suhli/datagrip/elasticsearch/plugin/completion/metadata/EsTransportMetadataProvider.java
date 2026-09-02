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
    private final Transport transport;
    private final EsClusterMetadata metadata;
    private volatile String esVersion;
    private volatile Map<String, String> targetKinds = Map.of();

    public EsTransportMetadataProvider(
            String datasourceId, String esVersion, Transport transport, URI endpoint) {
        this.datasourceId = datasourceId;
        this.esVersion = esVersion == null ? "" : esVersion;
        this.transport = transport;
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
    public void refreshClusterMetadata() throws Exception {
        String loaded = metadata.clusterVersion();
        if (loaded != null && !loaded.isBlank()) esVersion = loaded;
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
        Map<String, String> kinds = new LinkedHashMap<>();
        unique.values().forEach(target -> kinds.put(target.name(), target.kind()));
        targetKinds = Map.copyOf(kinds);
        return List.copyOf(unique.values());
    }

    @Override
    public List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) throws Exception {
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> merged = new LinkedHashMap<>();
        List<String> concrete = indexNames.stream()
                .filter(name -> "index".equals(targetKinds.getOrDefault(name, "index")))
                .distinct()
                .toList();
        if (!concrete.isEmpty()) {
            flatten(metadata.mappings(concrete), null, merged);
        }
        for (String target : indexNames) {
            String kind = targetKinds.get(target);
            if (!"alias".equals(kind) && !"data_stream".equals(kind)) continue;
            // A symbolic target may expand to backing indices. Keep its own coverage
            // so completion can filter fields by the request target.
            flatten(metadata.mappings(List.of(target)), target, merged);
        }
        return new ArrayList<>(merged.values());
    }

    private static void flatten(
            Map<String, JsonNode> mappings,
            String requestedTarget,
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> merged) {
        for (Map.Entry<String, JsonNode> entry : mappings.entrySet()) {
            String coverage = requestedTarget == null ? entry.getKey() : requestedTarget;
            for (MappingFlattener.Field field : MappingFlattener.flatten(entry.getValue())) {
                merged.merge(
                        field.name(),
                        new EsCompletionMetadataSnapshot.FieldInfo(
                                field.name(), Set.of(field.esType()), Set.of(coverage), field.multiField()),
                        EsTransportMetadataProvider::mergeField);
            }
        }
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

    @Override
    public void close() throws Exception {
        transport.close();
    }
}
