package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Types;
import java.net.URI;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesUrlOptionsIpv6AndDoesNotLeakSecrets() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("password", "top-secret");
        properties.setProperty("verifyTls", "false");
        EsJdbcUrl url = EsJdbcUrl.parse("jdbc:es-rest:https://[::1]:9443/proxy?connectTimeout=123", properties);

        assertEquals("https", url.endpoint().getScheme());
        assertEquals("https://[::1]:9443/proxy", url.endpoint().toString());
        assertEquals("/proxy", url.endpoint().getPath());
        assertEquals(123, url.connectTimeout().toMillis());
        assertFalse(url.verifyTls());
        assertFalse(url.toString().contains("top-secret"));
        assertThrows(SQLException.class,
                () -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?apiKey=secret", new Properties()));

        Properties prefix = new Properties();
        prefix.setProperty("pathPrefix", "/from-properties/");
        EsJdbcUrl http = EsJdbcUrl.parse("jdbc:es-rest://127.0.0.1:9200/from-url", prefix);
        assertEquals("http", http.endpoint().getScheme());
        assertEquals("/from-properties", http.endpoint().getPath());
        assertEquals("jdbc:es-rest://127.0.0.1:9200/from-properties", http.jdbcUrl());
    }

    @Test
    void parsesExactlyOneRestRequest() throws Exception {
        var request = RestRequestParser.parse("""
                POST /index/_search

                {"query":{"term":{"message":"escaped \\"{ value }\\""}}}
                """);
        assertEquals("POST", request.method());
        assertEquals("/index/_search", request.path());
        assertTrue(request.body().contains("{ value }"));
        assertThrows(SQLException.class,
                () -> RestRequestParser.parse("GET /a\n{}\nGET /b\n{}"));
        assertThrows(SQLException.class, () -> RestRequestParser.parse("SELECT * FROM index"));
    }

    @Test
    void flattensMappingsAndMultiFields() throws Exception {
        var fields = MappingFlattener.flatten(JSON.readTree("""
                {"mappings":{"properties":{"title":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
                "author":{"properties":{"name":{"type":"keyword"}}}}}}
                """));
        assertTrue(fields.stream().anyMatch(f -> f.name().equals("title.keyword") && f.multiField()));
        assertTrue(fields.stream().anyMatch(f -> f.name().equals("author.name") && f.jdbcType() == Types.VARCHAR));
        assertEquals(Types.DECIMAL, EsTypes.jdbcType("unsigned_long"));
        assertEquals(Types.JAVA_OBJECT, EsTypes.jdbcType("geo_shape"));
        assertEquals(Types.OTHER, EsTypes.jdbcType("future_type"));
    }

    @Test
    void mapsSearchHitUnionAndComplexFallback() throws Exception {
        TabularResult result = JsonResultMapper.map("""
                {"hits":{"hits":[
                  {"_id":"1","_source":{"name":"one","tags":["a","b"]}},
                  {"_id":"2","_source":{"count":2}}
                ]}}
                """);
        assertEquals(2, result.rows().size());
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("name")));
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("count")));
        int tags = java.util.stream.IntStream.range(0, result.columns().size())
                .filter(i -> result.columns().get(i).label().equals("tags")).findFirst().orElseThrow();
        assertEquals("[\"a\",\"b\"]", result.rows().get(0).get(tags));

        TabularResult aggregation = JsonResultMapper.map("""
                {"aggregations":{"by_level":{"buckets":[{"key":10,"doc_count":100}]}}}
                """);
        assertTrue(aggregation.columns().stream().anyMatch(c -> c.label().equals("key")));
        assertTrue(aggregation.columns().stream().anyMatch(c -> c.label().equals("doc_count")));

        TabularResult generic = JsonResultMapper.map("""
                [{"name":"A"},{"name":"B","level":null}]
                """);
        assertEquals(2, generic.rows().size());
        assertTrue(generic.columns().stream().anyMatch(c -> c.label().equals("level")));
    }

    @Test
    void exposesIndicesColumnsAndExecutesRestThroughJdbc() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200/proxy", new Properties());
        FakeTransport transport = new FakeTransport();
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "test-cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            try (var catalogs = connection.getMetaData().getCatalogs()) {
                assertTrue(catalogs.next());
                assertEquals("test-cluster", catalogs.getString("TABLE_CAT"));
            }
            try (var tables = connection.getMetaData().getTables("test-cluster", null, "%", null)) {
                assertTrue(tables.next());
                assertEquals("users", tables.getString("TABLE_NAME"));
            }
            try (var columns = connection.getMetaData().getColumns("test-cluster", null, "users", "%")) {
                assertTrue(columns.next());
                assertEquals("name", columns.getString("COLUMN_NAME"));
                assertEquals("keyword", columns.getString("TYPE_NAME"));
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("GET /users/_search\n{\"size\":1}")) {
                assertTrue(rows.next());
                assertEquals("Ada", rows.getString("name"));
            }
        }
        assertTrue(transport.closed);
        assertTrue(transport.requests.stream().allMatch(r -> r.uri().getPath().startsWith("/proxy/")));
    }

    @Test
    void mapsElasticsearchErrorsWithoutLeakingResponseDocuments() {
        var response = new Transport.Response(400, Map.of(), """
                {"error":{"type":"parsing_exception","reason":"bad request",
                "root_cause":[{"type":"json_parse","reason":"unexpected token"}]},
                "secret_document":"must-not-appear","status":400}
                """);
        SQLException exception = EsSqlException.from(response, "POST", "/users/_search");
        assertTrue(exception.getMessage().contains("HTTP 400"));
        assertTrue(exception.getMessage().contains("parsing_exception"));
        assertTrue(exception.getMessage().contains("unexpected token"));
        assertFalse(exception.getMessage().contains("must-not-appear"));
    }

    private static final class FakeTransport implements Transport {
        final java.util.ArrayList<Request> requests = new java.util.ArrayList<>();
        boolean closed;

        @Override
        public Response execute(Request request) {
            requests.add(request);
            String path = request.uri().getPath();
            if (path.endsWith("/_cat/indices")) {
                return response("""
                        [{"index":"users","health":"green","status":"open",
                          "docs.count":"1","store.size":"1kb"}]
                        """);
            }
            if (path.endsWith("/_mapping")) {
                return response("""
                        {"users":{"mappings":{"properties":{"name":{"type":"keyword"},
                        "profile":{"properties":{"country":{"type":"text"}}}}}}}
                        """);
            }
            if (path.endsWith("/users/_search")) {
                return response("""
                        {"hits":{"hits":[{"_index":"users","_id":"1",
                        "_source":{"name":"Ada"}}]}}
                        """);
            }
            return new Response(404, Map.of(), "{}");
        }

        private Response response(String body) {
            return new Response(200, Map.of(), body);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
