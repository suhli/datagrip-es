package io.github.suhli.datagrip.elasticsearch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResponseLimitsAndMappingTest {
    @Test
    void ordinaryEmptySearchIsZeroRowsButMeaningfulMetadataKeepsRaw() throws Exception {
        JsonResultMapper.MappedResponse ordinary = JsonResultMapper.mapResponse("""
                {"took":2,"timed_out":false,
                 "_shards":{"total":2,"successful":2,"skipped":0,"failed":0,"failures":[]},
                 "hits":{"total":{"value":0,"relation":"eq"},"max_score":null,"hits":[]}}
                """);
        assertTrue(ordinary.structured());
        assertTrue(ordinary.tabular().rows().isEmpty());
        assertNull(ordinary.rawBody());

        String[] meaningful = {
                "{\"hits\":{\"hits\":[]},\"suggest\":{\"name\":[]}}",
                "{\"hits\":{\"hits\":[]},\"profile\":{\"shards\":[]}}",
                "{\"hits\":{\"hits\":[]},\"pit_id\":\"pit-value\"}",
                "{\"timed_out\":true,\"hits\":{\"hits\":[]}}",
                "{\"_shards\":{\"failed\":1,\"failures\":[{\"reason\":\"safe\"}]},\"hits\":{\"hits\":[]}}",
                "{\"hits\":{\"hits\":[]},\"custom_section\":{\"value\":1}}"
        };
        for (String json : meaningful) {
            JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse(json);
            assertFalse(mapped.tabular().rows().isEmpty(), json);
            assertEquals(json, mapped.rawBody(), json);
            assertEquals("_response", mapped.tabular().columns().get(0).label(), json);
        }
    }

    @Test
    void hitsExposeAggregationsOnceEvenWhenRawResponseIsCapped() throws Exception {
        String largeValue = "x".repeat(JsonResultMapper.MAX_RAW_RESPONSE_BYTES + 100);
        String json = """
                {"hits":{"hits":[{"_id":"1","_source":{"name":"a"}},
                                  {"_id":"2","_source":{"name":"b"}}]},
                 "aggregations":{"large_metric":{"value":"%s"}}}
                """.formatted(largeValue);
        JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse(json);

        int aggregations = column(mapped.tabular(), "_aggregations");
        int response = column(mapped.tabular(), "_response");
        assertEquals(2, mapped.tabular().rows().size());
        assertTrue(mapped.tabular().rows().get(0).get(aggregations).toString().contains("large_metric"));
        assertTrue(mapped.tabular().rows().get(0).get(aggregations).toString().contains(largeValue));
        assertNull(mapped.tabular().rows().get(1).get(aggregations));
        assertTrue(mapped.tabular().rows().get(0).get(response).toString()
                .startsWith("<raw response omitted:"));
        assertNull(mapped.tabular().rows().get(1).get(response));
    }

    @Test
    void sizeZeroAggregationAlsoExposesStructuredAggregation() throws Exception {
        JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse("""
                {"hits":{"hits":[]},"aggregations":{
                  "by_status":{"buckets":[{"key":"ok","doc_count":3}]}}}
                """);
        int aggregations = column(mapped.tabular(), "_aggregations");
        assertTrue(mapped.tabular().rows().get(0).get(aggregations).toString().contains("by_status"));
    }

    @Test
    void validatesConfiguredResponseLimitAndAdvertisesDefault() throws Exception {
        assertEquals(EsJdbcUrl.DEFAULT_MAX_RESPONSE_BYTES,
                EsJdbcUrl.parse("jdbc:es-rest://localhost:9200", new Properties()).maxResponseBytes());
        assertThrows(java.sql.SQLException.class,
                () -> EsJdbcUrl.parse("jdbc:es-rest://localhost:9200?maxResponseBytes=-1",
                        new Properties()));
        assertTrue(java.util.Arrays.stream(new ElasticsearchDriver().getPropertyInfo(null, null))
                .anyMatch(info -> info.name.equals("maxResponseBytes")));
    }

    @Test
    void enforcesKnownAndChunkedLengthsAtExactByteBoundary() throws Exception {
        byte[] below = "123456789".getBytes(StandardCharsets.UTF_8);
        byte[] exact = "1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] above = "12345678901".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(exchange -> {
            byte[] body = switch (exchange.getRequestURI().getPath()) {
                case "/below" -> below;
                case "/exact", "/exact-chunked" -> exact;
                default -> above;
            };
            boolean chunked = exchange.getRequestURI().getPath().contains("chunked");
            exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
            exchange.getResponseBody().write(body);
        }, "/below", "/exact", "/exact-chunked", "/above", "/above-chunked");
        try {
            EsJdbcUrl config = config(server, 10);
            try (HttpTransport transport = new HttpTransport(config)) {
                assertEquals("123456789", get(transport, config, "/below").body());
                assertEquals("1234567890", get(transport, config, "/exact").body());
                assertEquals("1234567890", get(transport, config, "/exact-chunked").body());
                assertLimitExceeded(() -> get(transport, config, "/above"), 10);
                assertLimitExceeded(() -> get(transport, config, "/above-chunked"), 10);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gzipLimitUsesDecompressedBytesAndUnlimitedModeReadsAll() throws Exception {
        byte[] expanded = "a".repeat(1000).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(expanded);
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
        }, "/gzip");
        try {
            EsJdbcUrl limited = config(server, 100);
            try (HttpTransport transport = new HttpTransport(limited)) {
                assertLimitExceeded(() -> get(transport, limited, "/gzip"), 100);
            }
            EsJdbcUrl unlimited = config(server, 0);
            try (HttpTransport transport = new HttpTransport(unlimited)) {
                assertEquals(1000, get(transport, unlimited, "/gzip").body().length());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientAndJdbcConnectionRemainUsableAfterOversizedResponse() throws Exception {
        byte[] large = "{\"value\":\"too-large\"}".getBytes(StandardCharsets.UTF_8);
        byte[] ok = "{\"status\":\"green\"}".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(exchange -> {
            byte[] body = exchange.getRequestURI().getPath().equals("/large") ? large : ok;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }, "/large", "/ok");
        try {
            EsJdbcUrl config = config(server, 18);
            try (HttpTransport transport = new HttpTransport(config)) {
                assertLimitExceeded(() -> get(transport, config, "/large"), 18);
                assertEquals("{\"status\":\"green\"}", get(transport, config, "/ok").body());
            }

            HttpTransport transport = new HttpTransport(config);
            EsVersion version = new EsVersion("Elasticsearch", "8.17.0",
                    "elasticsearch", "cluster", "uuid");
            try (Connection connection = JdbcProxies.open(config, transport, version);
                 Statement statement = connection.createStatement()) {
                SQLDataException error = assertThrows(SQLDataException.class,
                        () -> statement.executeQuery("GET /large"));
                assertTrue(error.getMessage().contains("response exceeds configured maximum"));
                assertTrue(error.getMessage().contains("18"));
                assertFalse(error.getMessage().contains("too-large"));
                try (ResultSet result = statement.executeQuery("GET /ok")) {
                    assertTrue(result.next());
                    assertEquals("green", result.getString("status"));
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static int column(TabularResult result, String label) {
        for (int i = 0; i < result.columns().size(); i++) {
            if (result.columns().get(i).label().equals(label)) return i;
        }
        fail("Missing column " + label);
        return -1;
    }

    private static EsJdbcUrl config(HttpServer server, long limit) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("auth", "none");
        properties.setProperty("maxResponseBytes", Long.toString(limit));
        return EsJdbcUrl.parse(
                "jdbc:es-rest://127.0.0.1:" + server.getAddress().getPort(), properties);
    }

    private static Transport.Response get(HttpTransport transport, EsJdbcUrl config, String path)
            throws IOException {
        return transport.execute(new Transport.Request(
                "GET", config.endpoint().resolve(path), Map.of(), null));
    }

    private static void assertLimitExceeded(ThrowingIo action, long limit) {
        HttpTransport.ResponseTooLargeException error =
                assertThrows(HttpTransport.ResponseTooLargeException.class, action::run);
        assertTrue(error.getMessage().contains("response exceeds configured maximum"));
        assertTrue(error.getMessage().contains(Long.toString(limit)));
    }

    private static HttpServer server(ExchangeHandler handler, String... paths) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : paths) {
            server.createContext(path, exchange -> {
                try {
                    handler.handle(exchange);
                } finally {
                    exchange.close();
                }
            });
        }
        server.start();
        return server;
    }

    private static byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingIo {
        void run() throws IOException;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
