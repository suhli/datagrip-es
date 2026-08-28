package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Types;
import java.sql.Connection;
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
    void parsesCommentsAndMultipleRestRequests() throws Exception {
        var request = RestRequestParser.parse("""
                POST /index/_search

                {
                  // JSON body comment
                  "query":{"term":{"message":"escaped \\"{ value }\\""}}
                }
                """);
        assertEquals("POST", request.method());
        assertEquals("/index/_search", request.path());
        assertTrue(request.body().contains("{ value }"));

        var requests = RestRequestParser.parseAll("""
                // first request
                GET /a // request comment

                # second request
                POST /b
                {
                  "url": "https://example.test/a//b",
                  # hash comment
                  "enabled": true
                }
                """);
        assertEquals(2, requests.size());
        assertEquals("/a", requests.get(0).path());
        assertEquals("/b", requests.get(1).path());
        assertTrue(requests.get(1).body().contains("https://example.test/a//b"));
        assertFalse(requests.get(1).body().contains("hash comment"));
        assertThrows(SQLException.class, () -> RestRequestParser.parse("GET /a\nGET /b"));
        assertThrows(SQLException.class, () -> RestRequestParser.parse("SELECT * FROM index"));

        var ndjson = RestRequestParser.parse("""
                POST /_msearch
                {"index":"users"}
                {"query":{"match_all":{}}}
                """);
        assertEquals("POST", ndjson.method());
        assertEquals(2, ndjson.body().lines().count());
        assertTrue(ndjson.body().endsWith("\n"));
        assertTrue(RestRequestParser.isNdjsonPath("/users/_bulk?refresh=true"));
        assertThrows(SQLException.class, () -> RestRequestParser.parse("""
                POST /_bulk
                {"index":{}}
                not-json
                """));
    }

    @Test
    void translatesDataGripTableSelectWithoutElasticsearchSqlApi() throws Exception {
        var request = SqlSelectTranslator.translate("""
                SELECT *
                FROM "test-cluster"."logs-2026.08.28"
                ORDER BY "@timestamp" DESC
                LIMIT 50 OFFSET 10
                """, 0, 0);

        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/logs-2026.08.28/_search", request.path());
        var body = JSON.readTree(request.body());
        assertEquals(50, body.path("size").asInt());
        assertEquals(10, body.path("from").asInt());
        assertEquals("desc", body.path("sort").get(0).path("@timestamp").asText());

        var aliased = SqlSelectTranslator.translate(
                "SELECT t.* FROM \"logs-2026.08.28\" AS t", 0, 0);
        assertEquals("/logs-2026.08.28/_search", aliased.path());
        assertEquals(500, JSON.readTree(aliased.body()).path("size").asInt());

        var shortAlias = SqlSelectTranslator.translate(
                "SELECT t.* FROM \"logs-2026.08.28\" t WHERE status:200", 0, 0);
        assertTrue(JSON.readTree(shortAlias.body()).path("query").has("match"));

        var count = SqlSelectTranslator.translate(
                "SELECT count(*) AS total FROM `users`", 0, 0);
        assertEquals("GET", count.method());
        assertEquals("/users/_count", count.path());

        var filtered = SqlSelectTranslator.translate("""
                SELECT * FROM users
                WHERE status:200 AND (service.name:"checkout api" OR url.path:/orders/*)
                LIMIT 25
                """, 0, 0);
        var filteredBody = JSON.readTree(filtered.body());
        assertEquals(25, filteredBody.path("size").asInt());
        assertTrue(filteredBody.path("query").has("bool"));
    }

    @Test
    void parsesKqlBooleanRangeWildcardAndExistsQueries() throws Exception {
        Map<String, Object> query = KqlParser.parse("""
                NOT response.status >= 500 AND
                (host.name:web-* OR user.id:*) AND message:"request failed"
                """);
        String json = JSON.writeValueAsString(query);
        assertTrue(json.contains("must_not"));
        assertTrue(json.contains("\"range\""));
        assertTrue(json.contains("\"wildcard\""));
        assertTrue(json.contains("\"exists\""));
        assertTrue(json.contains("\"match_phrase\""));

        String nested = JSON.writeValueAsString(
                KqlParser.parse("user:{ first:\"Ada\" AND last:Lo* }"));
        assertTrue(nested.contains("\"nested\""));
        assertTrue(nested.contains("\"path\":\"user\""));
        assertTrue(nested.contains("\"user.first\""));
        assertTrue(nested.contains("\"user.last\""));
        assertThrows(SQLException.class, () -> KqlParser.parse("status:(200 OR"));
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

        String completeJson = """
                {"aggregations":{"by_level":{"buckets":[{"key":10,"doc_count":100}]}},
                 "_shards":{"total":2,"successful":2}}
                """;
        TabularResult withRaw = JsonResultMapper.mapWithRawResponse(completeJson);
        int rawColumn = java.util.stream.IntStream.range(0, withRaw.columns().size())
                .filter(i -> withRaw.columns().get(i).label().equals("_response"))
                .findFirst().orElseThrow();
        assertEquals("JSON", withRaw.columns().get(rawColumn).typeName());
        assertTrue(withRaw.rows().get(0).get(rawColumn).toString().contains("_shards"));

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
            assertTrue(connection.getMetaData().supportsMultipleResultSets());
            assertTrue(connection.getMetaData().supportsMultipleOpenResults());
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
                assertTrue(rows.getString("_response").contains("\"hits\""));
            }
            try (var statement = connection.createStatement()) {
                assertTrue(statement.execute("""
                        // data
                        GET /users/_search
                        {"size": 1}

                        // health
                        GET /_cluster/health
                        """));
                try (var rows = statement.getResultSet()) {
                    assertTrue(rows.next());
                    assertEquals("Ada", rows.getString("name"));
                }
                assertTrue(statement.getMoreResults());
                try (var health = statement.getResultSet()) {
                    assertTrue(health.next());
                    assertEquals("green", health.getString("status"));
                }
                assertFalse(statement.getMoreResults());
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
            if (path.endsWith("/_cluster/health")) {
                return response("""
                        {"cluster_name":"test-cluster","status":"green"}
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
