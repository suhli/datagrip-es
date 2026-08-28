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
                    "GET", url.endpoint().resolve(append(url.endpoint().getPath(), "/")), java.util.Map.of(), null));
            if (!response.successful()) {
                throw new SQLException("Product detection failed with HTTP " + response.status(), "08001");
            }
            JsonNode root = JSON.readTree(response.body());
            String number = root.path("version").path("number").asText("");
            String distribution = root.path("version").path("distribution").asText("elasticsearch");
            String tagline = root.path("tagline").asText("");
            if (number.isBlank() || !root.path("version").isObject()) {
                throw new SQLException("Endpoint is not an Elasticsearch-compatible product", "08001");
            }
            boolean openSearch = "opensearch".equalsIgnoreCase(distribution)
                    || tagline.toLowerCase(java.util.Locale.ROOT).contains("opensearch");
            String product = openSearch ? "OpenSearch" : "Elasticsearch";
            return new EsVersion(product, number, distribution,
                    root.path("cluster_name").asText(product),
                    root.path("cluster_uuid").asText(""));
        } catch (IOException e) {
            throw new SQLException("Cannot detect Elasticsearch product", "08001", e);
        }
    }

    private static String append(String prefix, String path) {
        String base = prefix == null || "/".equals(prefix) ? "" : prefix;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    public int major() {
        try { return Integer.parseInt(number.split("\\.")[0]); }
        catch (RuntimeException e) { return 0; }
    }
}
