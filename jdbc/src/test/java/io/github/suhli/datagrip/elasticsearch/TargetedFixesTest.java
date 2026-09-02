package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TargetedFixesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void isValidRejectsNegativeAndConvertsSecondsToMillis() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            SQLException negative = assertThrows(SQLException.class, () -> connection.isValid(-1));
            assertTrue(negative.getMessage().toLowerCase().contains("timeout"));

            assertTrue(connection.isValid(0));
            assertEquals(0, transport.lastTimeoutMillis.get());

            assertTrue(connection.isValid(1));
            assertEquals(1_000, transport.lastTimeoutMillis.get());

            assertTrue(connection.isValid(5));
            assertEquals(5_000, transport.lastTimeoutMillis.get());
        }
    }

    @Test
    void validatesQueryAndNetworkTimeoutArguments() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             Statement statement = connection.createStatement()) {
            SQLException query = assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1));
            assertEquals("HY092", query.getSQLState());
            statement.setQueryTimeout(Integer.MAX_VALUE);
            SQLException overflow = assertThrows(
                    SQLException.class, () -> statement.executeQuery("GET /too-slow"));
            assertEquals("HY092", overflow.getSQLState());
            statement.setQueryTimeout(0);

            SQLException nullExecutor = assertThrows(
                    SQLException.class, () -> connection.setNetworkTimeout(null, 1));
            assertEquals("HY009", nullExecutor.getSQLState());
            SQLException negative = assertThrows(
                    SQLException.class, () -> connection.setNetworkTimeout(Runnable::run, -1));
            assertEquals("HY092", negative.getSQLState());

            connection.setNetworkTimeout(Runnable::run, 0);
            assertEquals(0, connection.getNetworkTimeout());
            connection.setNetworkTimeout(Runnable::run, 1234);
            assertEquals(1234, connection.getNetworkTimeout());
        }
    }

    @Test
    void jdbcLikePatternEscapeAndLiteralUnderscore() {
        assertTrue(JdbcLikePattern.matches("game_logs", "game_logs"));
        assertTrue(JdbcLikePattern.matches("gameXlogs", "game_logs"));
        assertTrue(JdbcLikePattern.matches("game_logs", "game\\_logs"));
        assertFalse(JdbcLikePattern.matches("gameXlogs", "game\\_logs"));
        assertTrue(JdbcLikePattern.matches("foo%bar", "foo\\%bar"));
        assertFalse(JdbcLikePattern.matches("fooxbar", "foo\\%bar"));
        assertTrue(JdbcLikePattern.hasUnescapedWildcards("game_logs"));
        assertFalse(JdbcLikePattern.hasUnescapedWildcards("game\\_logs"));
        assertEquals("game_logs", JdbcLikePattern.literalOrNull("game\\_logs"));
        assertNull(JdbcLikePattern.literalOrNull("game_logs"));
        assertNull(JdbcLikePattern.literalOrNull("game%"));
    }

    @Test
    void underscoreIndexNameRequestsConcreteMappingPath() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        TrackingTransport transport = new TrackingTransport(List.of(
                "game_logs", "client_plogs", "login_server", "foo-bar", "foo.bar"));
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             ResultSet columns = connection.getMetaData().getColumns("cluster", null, "game_logs", "%")) {
            assertTrue(columns.next());
            assertEquals("message", columns.getString("COLUMN_NAME"));
        }
        assertTrue(transport.mappingPaths.stream().anyMatch(p -> p.equals("/game_logs/_mapping")
                || p.startsWith("/game_logs,") || p.contains("/game_logs/_mapping")
                || p.equals("/game_logs/_mapping")));
        assertTrue(transport.mappingPaths.stream().allMatch(p -> !p.contains("game?logs")));
        assertTrue(transport.mappingPaths.stream().anyMatch(p -> p.contains("game_logs")));
    }

    @Test
    void getTablesAndGetColumnsAgreeOnUnderscoreIndex() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        TrackingTransport transport = new TrackingTransport(List.of("game_logs", "gameXlogs"));
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version)) {
            try (ResultSet tables = connection.getMetaData().getTables("cluster", null, "game_logs", null)) {
                assertTrue(tables.next());
                assertEquals("game_logs", tables.getString("TABLE_NAME"));
            }
            try (ResultSet columns = connection.getMetaData().getColumns("cluster", null, "game_logs", "%")) {
                assertTrue(columns.next());
                assertEquals("game_logs", columns.getString("TABLE_NAME"));
            }
        }
        assertTrue(transport.mappingPaths.stream().allMatch(p -> p.contains("game_logs")));
        assertFalse(transport.mappingPaths.stream().anyMatch(p -> p.contains("game?logs")));
    }

    @Test
    void escapedUnderscoreMatchesLiteralIndexOnly() throws Exception {
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        TrackingTransport transport = new TrackingTransport(List.of("game_logs", "gameXlogs"));
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             ResultSet tables = connection.getMetaData().getTables("cluster", null, "game\\_logs", null)) {
            assertTrue(tables.next());
            assertEquals("game_logs", tables.getString("TABLE_NAME"));
            assertFalse(tables.next());
        }
    }

    @Test
    void mappingRequestsAreBatched() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 123; i++) {
            names.add(String.format("idx-%03d", i));
        }
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        TrackingTransport transport = new TrackingTransport(names);
        EsVersion version = new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             ResultSet columns = connection.getMetaData().getColumns("cluster", null, "%", "%")) {
            assertTrue(columns.next());
        }
        assertEquals(3, transport.mappingPaths.size());
        assertEquals(50, countIndicesInPath(transport.mappingPaths.get(0)));
        assertEquals(50, countIndicesInPath(transport.mappingPaths.get(1)));
        assertEquals(23, countIndicesInPath(transport.mappingPaths.get(2)));
    }

    @Test
    void getRowReturnsZeroBeforeFirstAndAfterLast() throws Exception {
        TabularResult tabular = JsonResultMapper.map("""
                {"hits":{"hits":[
                  {"_source":{"n":1}},
                  {"_source":{"n":2}}
                ]}}
                """);
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties());
        FakeSearchTransport transport = new FakeSearchTransport(JSON.writeValueAsString(
                Map.of("hits", Map.of("hits", List.of(
                        Map.of("_source", Map.of("n", 1)),
                        Map.of("_source", Map.of("n", 2)))))));
        EsVersion version = new EsVersion("Elasticsearch", "8.0.0", "elasticsearch", "c", "u");
        try (Connection connection = JdbcProxies.open(config, transport, version);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("GET /x/_search")) {
            assertEquals(0, rs.getRow());
            assertTrue(rs.next());
            assertEquals(1, rs.getRow());
            assertTrue(rs.next());
            assertEquals(2, rs.getRow());
            assertFalse(rs.next());
            assertEquals(0, rs.getRow());
        }
        assertEquals(2, tabular.rows().size());
    }

    @Test
    void structuredSearchDoesNotRetainRawResponse() throws Exception {
        JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse("""
                {"hits":{"hits":[{"_source":{"name":"a"}}]}}
                """);
        assertTrue(mapped.structured());
        assertNull(mapped.rawBody());
        assertFalse(mapped.tabular().columns().stream().anyMatch(c -> c.label().equals("_response")));
        assertNull(mapped.tabular().rawBody());
    }

    @Test
    void unstructuredFallbackKeepsResponseAndRespectsSizeLimit() throws Exception {
        JsonResultMapper.MappedResponse small = JsonResultMapper.mapResponse("{}");
        assertFalse(small.structured());
        assertEquals("_response", small.tabular().columns().get(0).label());

        String huge = "x".repeat(JsonResultMapper.MAX_RAW_RESPONSE_BYTES + 10);
        String omitted = JsonResultMapper.truncateRaw(huge);
        assertTrue(omitted.startsWith("<raw response omitted:"));
    }

    @Test
    void realHttpClientCancelAbortsBlockedRequest() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/block", exchange -> {
            hits.incrementAndGet();
            arrived.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        Properties properties = new Properties();
        properties.setProperty("auth", "none");
        properties.setProperty("responseTimeout", "60000");
        EsJdbcUrl config = EsJdbcUrl.parse("jdbc:es-rest://127.0.0.1:" + port, properties);
        try (Connection connection = JdbcProxies.open(config, new HttpTransport(config),
                new EsVersion("Elasticsearch", "8.17.0", "elasticsearch", "cluster", "uuid"))) {
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
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                long started = System.nanoTime();
                statement.cancel();
                SQLException error = future.get(5, TimeUnit.SECONDS);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                assertNotNull(error);
                assertTrue(error.getMessage().toLowerCase().contains("cancel"), error.getMessage());
                assertTrue(elapsedMs < 15_000, "cancel should not wait for response timeout: " + elapsedMs);
                release.countDown();
                try (ResultSet ok = other.executeQuery("GET /ok")) {
                    assertTrue(ok.next());
                    assertEquals("green", ok.getString("status"));
                }
                try (ResultSet again = statement.executeQuery("GET /ok")) {
                    assertTrue(again.next());
                    assertEquals("green", again.getString("status"));
                }
                executor.shutdownNow();
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
        assertTrue(hits.get() >= 1);
    }

    private static int countIndicesInPath(String path) {
        String prefix = path.startsWith("/") ? path.substring(1) : path;
        int mapping = prefix.indexOf("/_mapping");
        String indices = mapping < 0 ? prefix : prefix.substring(0, mapping);
        return indices.isEmpty() ? 0 : indices.split(",").length;
    }

    private static final class RecordingTransport implements Transport {
        final AtomicInteger lastTimeoutMillis = new AtomicInteger(-1);

        @Override
        public Response execute(Request request) {
            return execute(request, ExecuteOptions.none());
        }

        @Override
        public Response execute(Request request, ExecuteOptions options) {
            lastTimeoutMillis.set(options == null ? 0 : options.timeoutMillis());
            return new Response(200, Map.of(),
                    "{\"cluster_name\":\"cluster\",\"version\":{\"number\":\"8.17.0\"},\"status\":\"green\"}");
        }

        @Override
        public void close() {}
    }

    private static final class TrackingTransport implements Transport {
        final List<String> indices;
        final List<String> mappingPaths = new ArrayList<>();

        TrackingTransport(List<String> indices) {
            this.indices = indices;
        }

        @Override
        public Response execute(Request request) {
            String path = request.uri().getPath();
            if (path.contains("/_cat/indices")) {
                StringBuilder body = new StringBuilder("[");
                for (int i = 0; i < indices.size(); i++) {
                    if (i > 0) body.append(',');
                    body.append("{\"index\":\"").append(indices.get(i))
                            .append("\",\"health\":\"green\",\"status\":\"open\",")
                            .append("\"docs.count\":\"1\",\"store.size\":\"1kb\"}");
                }
                body.append(']');
                return new Response(200, Map.of(), body.toString());
            }
            if (path.contains("/_mapping")) {
                mappingPaths.add(path);
                String indicesPart = path.substring(1, path.indexOf("/_mapping"));
                StringBuilder body = new StringBuilder("{");
                String[] parts = indicesPart.split(",");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) body.append(',');
                    body.append('"').append(parts[i]).append("\":{\"mappings\":{\"properties\":{")
                            .append("\"message\":{\"type\":\"keyword\"}}}}");
                }
                body.append('}');
                return new Response(200, Map.of(), body.toString());
            }
            return new Response(404, Map.of(), "{}");
        }

        @Override
        public void close() {}
    }

    private static final class FakeSearchTransport implements Transport {
        private final String body;

        FakeSearchTransport(String body) {
            this.body = body;
        }

        @Override
        public Response execute(Request request) {
            return new Response(200, Map.of(), body);
        }

        @Override
        public void close() {}
    }
}
