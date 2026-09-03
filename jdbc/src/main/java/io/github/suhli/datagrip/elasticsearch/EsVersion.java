package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.SQLException;

public record EsVersion(String product, String number, String distribution,
                        String clusterName, String clusterUuid) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static EsVersion detect(Transport transport, EsJdbcUrl url) throws SQLException {
        try {
            Transport.Response response = transport.execute(new Transport.Request(
                    "GET", EsUris.resolve(url.endpoint(), "/"), java.util.Map.of(), null));
            if (!response.successful()) {
                throw new SQLException("Product detection failed with HTTP " + response.status(), "08001");
            }
            JsonNode root = JSON.readTree(response.body());
            String number = root.path("version").path("number").asText("");
            String distribution = root.path("version").path("distribution").asText("");
            String tagline = root.path("tagline").asText("");
            if (number.isBlank() || !root.path("version").isObject()) {
                throw new SQLException("Endpoint is not an Elasticsearch-compatible product", "08001");
            }
            boolean openSearch = "opensearch".equalsIgnoreCase(distribution)
                    || tagline.toLowerCase(java.util.Locale.ROOT).contains("opensearch");
            boolean elasticHeader = response.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("X-Elastic-Product"))
                    .flatMap(entry -> entry.getValue().stream())
                    .anyMatch("Elasticsearch"::equalsIgnoreCase);
            boolean elasticsearch = "elasticsearch".equalsIgnoreCase(distribution)
                    || tagline.contains("You Know, for Search") || elasticHeader;
            if (!openSearch && !elasticsearch) {
                throw new SQLException("Endpoint is not Elasticsearch or OpenSearch", "08001");
            }
            String product = openSearch ? "OpenSearch" : "Elasticsearch";
            return new EsVersion(product, number, distribution,
                    root.path("cluster_name").asText(product),
                    root.path("cluster_uuid").asText(""));
        } catch (IOException e) {
            throw new SQLException("Cannot detect Elasticsearch product", "08001", e);
        }
    }

    public int major() {
        try { return Integer.parseInt(number.split("\\.")[0]); }
        catch (RuntimeException e) { return 0; }
    }

    public int minor() {
        try { return Integer.parseInt(number.split("\\.")[1]); }
        catch (RuntimeException e) { return 0; }
    }

    /** Elasticsearch 7.10+ supports case_insensitive on wildcard queries. */
    public boolean supportsCaseInsensitiveWildcard() {
        if ("OpenSearch".equals(product)) return true;
        if (major() > 7) return true;
        if (major() == 7) return minor() >= 10;
        return false;
    }
}
