package io.github.suhli.datagrip.elasticsearch;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchIntegrationTest {
    @Test
    void connectsIntrospectsSearchesAndReportsErrors() throws Exception {
        String endpoint = System.getenv("ES_TEST_URL");
        String jdbcUrl = toJdbcUrl(endpoint);
        Properties properties = new Properties();
        putIfPresent(properties, "user", System.getenv("ES_TEST_USER"));
        putIfPresent(properties, "password", System.getenv("ES_TEST_PASSWORD"));

        Class.forName("io.github.suhli.datagrip.elasticsearch.ElasticsearchDriver");
        String index = "datagrip_rest_it";
        try (var connection = DriverManager.getConnection(jdbcUrl, properties);
             var statement = connection.createStatement()) {
            try {
                statement.execute("""
                        PUT /datagrip_rest_it
                        {"mappings":{"properties":{"name":{"type":"keyword"},
                        "profile":{"properties":{"level":{"type":"integer"}}}}}}
                        """);
                statement.execute("""
                        POST /datagrip_rest_it/_doc/1?refresh=true
                        {"name":"Ada","profile":{"level":10}}
                        """);

                try (var tables = connection.getMetaData().getTables(null, null, index, null)) {
                    assertTrue(tables.next());
                    assertEquals(index, tables.getString("TABLE_NAME"));
                }
                try (var columns = connection.getMetaData().getColumns(null, null, index, "%")) {
                    boolean foundNested = false;
                    while (columns.next()) {
                        foundNested |= "profile.level".equals(columns.getString("COLUMN_NAME"));
                    }
                    assertTrue(foundNested);
                }
                try (var rows = statement.executeQuery("""
                        GET /datagrip_rest_it/_search
                        {"query":{"match_all":{}}}
                        """)) {
                    assertTrue(rows.next());
                    assertEquals("Ada", rows.getString("name"));
                    assertEquals(10, rows.getInt("profile.level"));
                }
                SQLException error = assertThrows(SQLException.class,
                        () -> statement.execute("GET /datagrip_rest_it/_not_an_api"));
                assertTrue(error.getMessage().contains("HTTP 400")
                        || error.getMessage().contains("HTTP 404"));
            } finally {
                statement.execute("DELETE /" + index);
            }
        }
    }

    private static String toJdbcUrl(String endpoint) {
        URI uri = URI.create(endpoint);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return "jdbc:es-rest:" + endpoint;
        }
        return "jdbc:es-rest:" + endpoint.substring("http:".length());
    }

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) properties.setProperty(key, value);
    }
}
