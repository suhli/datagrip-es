package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only Elasticsearch cluster metadata helpers shared by JDBC introspection
 * and IDE completion. Performs synchronous HTTP through {@link Transport}; callers
 * must not invoke this from the EDT.
 */
public final class EsClusterMetadata {
    public static final int MAPPING_BATCH_SIZE = 50;
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(20);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Transport transport;
    private final URI endpoint;
    private final MetadataCache cache;
    private volatile String clusterVersion = "";

    public EsClusterMetadata(Transport transport, URI endpoint) {
        this(transport, endpoint, DEFAULT_TTL);
    }

    public EsClusterMetadata(Transport transport, URI endpoint, Duration ttl) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.cache = new MetadataCache(ttl == null ? DEFAULT_TTL : ttl);
    }

    public List<IndexInfo> indices() throws IOException {
        List<MetadataCache.IndexInfo> cached = cache.indicesIfFresh();
        if (cached != null) {
            return cached.stream()
                    .map(i -> new IndexInfo(i.name(), i.health(), i.status(), i.docsCount(), i.storeSize(), "index"))
                    .toList();
        }
        Transport.Response response = transport.execute(new Transport.Request(
                "GET",
                EsUris.resolve(endpoint, "/_cat/indices?format=json&h=index,health,status,docs.count,store.size&expand_wildcards=all"),
                Map.of(),
                null));
        if (!response.successful()) {
            throw new IOException("Listing indices failed with HTTP " + response.status());
        }
        List<MetadataCache.IndexInfo> loaded = new ArrayList<>();
        List<IndexInfo> result = new ArrayList<>();
        for (JsonNode item : JSON.readTree(response.body())) {
            MetadataCache.IndexInfo info = new MetadataCache.IndexInfo(
                    item.path("index").asText(),
                    item.path("health").asText(""),
                    item.path("status").asText(""),
                    item.path("docs.count").asText(""),
                    item.path("store.size").asText(""));
            loaded.add(info);
            result.add(new IndexInfo(info.name(), info.health(), info.status(),
                    info.docsCount(), info.storeSize(), "index"));
        }
        cache.putIndices(loaded);
        return List.copyOf(result);
    }

    public List<NamedObject> aliases() throws IOException {
        Transport.Response response = transport.execute(new Transport.Request(
                "GET",
                EsUris.resolve(endpoint, "/_cat/aliases?format=json&h=alias,index"),
                Map.of(),
                null));
        if (!response.successful()) {
            throw new IOException("Listing aliases failed with HTTP " + response.status());
        }
        Map<String, NamedObject> unique = new LinkedHashMap<>();
        for (JsonNode item : JSON.readTree(response.body())) {
            String alias = item.path("alias").asText();
            if (!alias.isBlank()) {
                unique.putIfAbsent(alias, new NamedObject(alias, "alias"));
            }
        }
        return List.copyOf(unique.values());
    }

    public List<NamedObject> dataStreams() throws IOException {
        Transport.Response response = transport.execute(new Transport.Request(
                "GET",
                EsUris.resolve(endpoint, "/_data_stream"),
                Map.of(),
                null));
        if (!response.successful()) {
            return List.of();
        }
        List<NamedObject> result = new ArrayList<>();
        for (JsonNode item : JSON.readTree(response.body()).path("data_streams")) {
            String name = item.path("name").asText();
            if (!name.isBlank()) result.add(new NamedObject(name, "data_stream"));
        }
        return List.copyOf(result);
    }

    public Map<String, JsonNode> mappings(List<String> indexNames) throws IOException {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String index : indexNames) {
            JsonNode cached = cache.mappingIfFresh(index);
            if (cached != null) result.put(index, cached);
            else missing.add(index);
        }
        for (int offset = 0; offset < missing.size(); offset += MAPPING_BATCH_SIZE) {
            List<String> batch = missing.subList(offset, Math.min(offset + MAPPING_BATCH_SIZE, missing.size()));
            String path = "/" + String.join(",", batch) + "/_mapping";
            Transport.Response response = transport.execute(new Transport.Request(
                    "GET", EsUris.resolve(endpoint, path), Map.of(), null));
            if (!response.successful()) {
                throw new IOException("Mapping request failed with HTTP " + response.status()
                        + " for [" + String.join(",", batch) + "]");
            }
            JsonNode root = JSON.readTree(response.body());
            root.properties().forEach(entry -> {
                result.put(entry.getKey(), entry.getValue());
                cache.putMapping(entry.getKey(), entry.getValue());
            });
        }
        return Map.copyOf(result);
    }

    public String clusterVersion() throws IOException {
        String cached = clusterVersion;
        if (!cached.isBlank()) return cached;
        Transport.Response response = transport.execute(new Transport.Request(
                "GET", EsUris.resolve(endpoint, "/"), Map.of(), null));
        if (!response.successful()) {
            throw new IOException("Reading cluster version failed with HTTP " + response.status());
        }
        String loaded = JSON.readTree(response.body()).path("version").path("number").asText("");
        if (!loaded.isBlank()) clusterVersion = loaded;
        return loaded;
    }

    public void invalidate() {
        cache.invalidate();
        clusterVersion = "";
    }

    public record IndexInfo(
            String name, String health, String status, String docsCount, String storeSize, String kind) {}

    public record NamedObject(String name, String kind) {}
}
