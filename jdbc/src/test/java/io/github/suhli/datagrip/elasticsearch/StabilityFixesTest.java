package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StabilityFixesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void metadataCacheRefreshesAfterTtl() throws Exception {
        MetadataCache cache = new MetadataCache(Duration.ofMillis(50));
        cache.putIndices(List.of(new MetadataCache.IndexInfo("a", "", "", "", "")));
        assertEquals(1, cache.indicesIfFresh().size());
        Thread.sleep(60);
        assertNull(cache.indicesIfFresh());
        cache.invalidate();
        assertNull(cache.indicesIfFresh());
    }

    @Test
    void getColumnsRequestsIndexSpecificMapping() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        TrackingTransport transport = new TrackingTransport();
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            try (ResultSet columns = connection.getMetaData().getColumns("cluster", null, "users", "%")) {
                assertTrue(columns.next());
                assertEquals("name", columns.getString("COLUMN_NAME"));
            }
        }
        assertTrue(transport.requests.stream().anyMatch(r -> r.uri().getPath().endsWith("/users/_mapping")));
        assertFalse(transport.requests.stream().anyMatch(r -> r.uri().getPath().endsWith("/_mapping")
                && !r.uri().getPath().contains("/users")));
    }

    @Test
    void isValidChecksElasticsearch() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        FakeTransport transport = new FakeTransport(true);
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            assertTrue(connection.isValid(5));
        }
        transport.failPing = true;
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            assertFalse(connection.isValid(5));
        }
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            connection.close();
            assertFalse(connection.isValid(5));
        }
    }

    @Test
    void queryTimeoutHasPriorityOverNetworkTimeout() throws Exception {
        SlowTransport transport = new SlowTransport(500);
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            connection.setNetworkTimeout(Runnable::run, 30_000);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(1);
                SQLTimeoutException timeout = assertThrows(SQLTimeoutException.class,
                        () -> statement.executeQuery("GET /slow"));
                assertTrue(timeout.getMessage().contains("GET"));
                assertTrue(timeout.getMessage().contains("/slow"));
                assertFalse(timeout.getMessage().contains("secret"));
            }
        }
    }

    @Test
    void cancelAbortsRunningRequest() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            try (Statement statement = connection.createStatement();
                 Statement other = connection.createStatement()) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<SQLException> future = executor.submit(() -> {
                    try {
                        statement.executeQuery("GET /block");
                        return null;
                    } catch (SQLException e) {
                        return e;
                    }
                });
                assertTrue(transport.started.await(2, TimeUnit.SECONDS));
                statement.cancel();
                SQLException error = future.get(5, TimeUnit.SECONDS);
                assertNotNull(error);
                transport.release();
                assertDoesNotThrow(() -> other.executeQuery("GET /users/_search\n{\"size\":1}"));
                executor.shutdownNow();
            }
        }
    }

    @Test
    void emptySearchReturnsZeroRowsWithoutResponseColumn() throws Exception {
        JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse("""
                {"took":1,"hits":{"total":{"value":0},"hits":[]}}
                """);
        assertTrue(mapped.structured());
        assertEquals(0, mapped.tabular().rows().size());
        assertFalse(mapped.tabular().columns().stream().anyMatch(c -> c.label().equals("_response")));
    }

    @Test
    void nullGettersDoNotThrow() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        NullTransport transport = new NullTransport();
        EsVersion version = new EsVersion("Elasticsearch", "8.0.0", "elasticsearch", "c", "u");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("GET /users/_search")) {
            assertTrue(rs.next());
            assertNull(rs.getObject("value"));
            assertTrue(rs.wasNull());
            assertNull(rs.getString("value"));
            assertTrue(rs.wasNull());
            assertNull(rs.getBigDecimal("value"));
            assertTrue(rs.wasNull());
            assertNull(rs.getDate("value"));
            assertTrue(rs.wasNull());
            assertNull(rs.getTime("value"));
            assertTrue(rs.wasNull());
            assertNull(rs.getTimestamp("value"));
            assertTrue(rs.wasNull());
            assertEquals(0, rs.getInt("value"));
            assertTrue(rs.wasNull());
            assertFalse(rs.getBoolean("flag"));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void wildcardOmitsCaseInsensitiveOnEs79() throws Exception {
        EsVersion old = new EsVersion("Elasticsearch", "7.9.0", "elasticsearch", "c", "u");
        EsVersion supported = new EsVersion("Elasticsearch", "7.10.0", "elasticsearch", "c", "u");
        String oldJson = JSON.writeValueAsString(KqlParser.parse("host:web-*", old));
        String newJson = JSON.writeValueAsString(KqlParser.parse("host:web-*", supported));
        assertFalse(oldJson.contains("case_insensitive"));
        assertTrue(newJson.contains("case_insensitive"));
    }

    @Test
    void rejectsCredentialLikeUrlParameters() {
        assertThrows(SQLException.class,
                () -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?header.Authorization=ApiKey%20SECRET", new Properties()));
        assertThrows(SQLException.class,
                () -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?HEADER.AUTHORIZATION=x", new Properties()));
        assertThrows(SQLException.class,
                () -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?headers=Authorization:%20x", new Properties()));
        assertDoesNotThrow(() -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?verifyTls=false", new Properties()));
    }

    @Test
    void metadataColumnsUseCorrectJdbcTypes() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        FakeTransport transport = new FakeTransport(true);
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             ResultSet columns = connection.getMetaData().getColumns("cluster", null, "users", "%")) {
            ResultSetMetaData meta = columns.getMetaData();
            assertEquals(Types.VARCHAR, meta.getColumnType(columns.findColumn("TABLE_NAME")));
            assertEquals(Types.INTEGER, meta.getColumnType(columns.findColumn("DATA_TYPE")));
            assertEquals(Types.INTEGER, meta.getColumnType(columns.findColumn("NULLABLE")));
            assertEquals(Types.INTEGER, meta.getColumnType(columns.findColumn("ORDINAL_POSITION")));
        }
    }

    @Test
    void heterogeneousValuesPromoteToVarchar() throws Exception {
        TabularResult result = JsonResultMapper.map("""
                {"hits":{"hits":[
                  {"_source":{"value":1}},
                  {"_source":{"value":"unknown"}}
                ]}}
                """);
        TabularResult.Column value = result.columns().stream()
                .filter(c -> c.label().equals("value")).findFirst().orElseThrow();
        assertEquals(Types.VARCHAR, value.jdbcType());
    }

    @Test
    void sourceAndFieldsDoNotOverwrite() throws Exception {
        TabularResult result = JsonResultMapper.map("""
                {"hits":{"hits":[{"_source":{"name":"a"},"fields":{"name":["A"]}}]}}
                """);
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("name")));
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("fields.name")));
        assertEquals("a", result.rows().get(0).get(findColumn(result, "name")));
        assertEquals("[\"A\"]", result.rows().get(0).get(findColumn(result, "fields.name")).toString());
    }

    @Test
    void bulkNdjsonParsesAsSingleStatement() throws Exception {
        var request = RestRequestParser.parse("""
                POST /_bulk
                {"index":{"_index":"foo"}}
                {"name":"A"}
                {"index":{"_index":"foo"}}
                {"name":"B"}
                """);
        assertEquals(4, request.body().lines().filter(line -> !line.isBlank()).count());
        var all = RestRequestParser.parseAll("""
                POST /_bulk
                {"index":{"_index":"foo"}}
                {"name":"A"}

                GET /_cluster/health
                """);
        assertEquals(2, all.size());
        assertTrue(all.get(0).body().contains("\"name\":\"A\""));
    }

    private static int findColumn(TabularResult result, String label) {
        for (int i = 0; i < result.columns().size(); i++) {
            if (result.columns().get(i).label().equals(label)) return i;
        }
        throw new AssertionError(label);
    }

    private static final class TrackingTransport implements Transport {
        final List<Request> requests = new ArrayList<>();

        @Override
        public Response execute(Request request) {
            requests.add(request);
            String path = request.uri().getPath();
            if (path.endsWith("/_cat/indices")) {
                return json("""
                        [{"index":"users","health":"green","status":"open",
                          "docs.count":"1","store.size":"1kb"}]
                        """);
            }
            if (path.endsWith("/users/_mapping")) {
                return json("""
                        {"users":{"mappings":{"properties":{"name":{"type":"keyword"}}}}}
                        """);
            }
            return new Response(404, Map.of(), "{}");
        }

        private Response json(String body) {
            return new Response(200, Map.of(), body);
        }

        @Override
        public void close() {}
    }

    private static final class FakeTransport implements Transport {
        boolean failPing;

        FakeTransport(boolean ignored) {}

        @Override
        public Response execute(Request request) {
            if (failPing && request.uri().getPath().endsWith("/")) {
                throw new RuntimeException("network down");
            }
            String path = request.uri().getPath();
            if (path.endsWith("/_cat/indices")) {
                return json("""
                        [{"index":"users","health":"green","status":"open",
                          "docs.count":"1","store.size":"1kb"}]
                        """);
            }
            if (path.endsWith("/users/_mapping")) {
                return json("""
                        {"users":{"mappings":{"properties":{"name":{"type":"keyword"}}}}}
                        """);
            }
            if (path.endsWith("/users/_search")) {
                return json("""
                        {"hits":{"hits":[{"_source":{"name":"Ada"}}]}}
                        """);
            }
            if (path.endsWith("/_cluster/health") || path.endsWith("/")) {
                return json("{\"status\":\"green\",\"cluster_name\":\"cluster\"}");
            }
            return new Response(404, Map.of(), "{}");
        }

        private Response json(String body) {
            return new Response(200, Map.of(), body);
        }

        @Override
        public void close() {}
    }

    private static final class SlowTransport implements Transport {
        private final long delayMillis;

        SlowTransport(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public Response execute(Request request, ExecuteOptions options) throws java.io.IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.net.SocketTimeoutException("timed out");
        }

        @Override
        public Response execute(Request request) throws java.io.IOException {
            return execute(request, null);
        }

        @Override
        public void close() {}
    }

    private static final class NullTransport implements Transport {
        @Override
        public Response execute(Request request) {
            return new Response(200, Map.of(), """
                    {"hits":{"hits":[{"_source":{"value":null,"flag":null}}]}}
                    """);
        }

        @Override
        public void close() {}
    }

    private static final class BlockingTransport implements Transport {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<Transport.Cancellation> cancellation = new AtomicReference<>();
        final AtomicBoolean release = new AtomicBoolean();

        @Override
        public Response execute(Request request, ExecuteOptions options) throws java.io.IOException {
            if (options != null && options.cancellation() != null) {
                cancellation.set(options.cancellation());
            }
            started.countDown();
            while (!release.get()) {
                if (options != null && options.cancellation() != null && options.cancellation().isCancelled()) {
                    throw new java.io.IOException("cancelled");
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted", e);
                }
            }
            return new Response(200, Map.of(), """
                    {"hits":{"hits":[{"_source":{"name":"Ada"}}]}}
                    """);
        }

        void release() {
            release.set(true);
        }

        @Override
        public Response execute(Request request) throws java.io.IOException {
            return execute(request, null);
        }

        @Override
        public void close() {}
    }
}
