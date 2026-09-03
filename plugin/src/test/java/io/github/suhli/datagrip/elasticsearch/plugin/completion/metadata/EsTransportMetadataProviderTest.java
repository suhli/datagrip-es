package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.suhli.datagrip.elasticsearch.EsJdbcUrl;
import io.github.suhli.datagrip.elasticsearch.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsTransportMetadataProviderTest {
    @Test
    void basicAuthLoadsVersionTargetsAndRealMultiFieldsThroughPathPrefix() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(authorization);
        try {
            Properties properties = new Properties();
            properties.setProperty("auth", "basic");
            properties.setProperty("user", "alice");
            properties.setProperty("password", "secret");
            properties.setProperty("pathPrefix", "/proxy");
            EsJdbcUrl config = EsJdbcUrl.parse(
                    "jdbc:es-rest://127.0.0.1:" + server.getAddress().getPort(), properties);
            try (EsTransportMetadataProvider provider = new EsTransportMetadataProvider(
                    "ds", "", new HttpTransport(config), config.endpoint())) {
                provider.refreshClusterMetadata();
                assertEquals("8.17.3", provider.esVersion());
                var targets = provider.listTargets();
                assertTrue(targets.stream().anyMatch(t -> t.name().equals("index-a") && t.kind().equals("index")));
                assertTrue(targets.stream().anyMatch(t -> t.name().equals("alias-a") && t.kind().equals("alias")));
                assertTrue(targets.stream().anyMatch(t -> t.name().equals("stream-a") && t.kind().equals("data_stream")));

                var fields = provider.loadFields(List.of("index-a"));
                assertTrue(fields.stream().anyMatch(f -> f.path().equals("data.ip") && !f.multiField()));
                assertTrue(fields.stream().anyMatch(f -> f.path().equals("data.ip.keyword") && f.multiField()));
                assertTrue(fields.stream().anyMatch(f -> f.path().equals("title.raw") && f.multiField()));

                var aliasFields = provider.loadFields(List.of("alias-a"));
                assertTrue(aliasFields.stream().allMatch(f -> f.indexCoverage().contains("alias-a")));
            }
            assertEquals("Basic YWxpY2U6c2VjcmV0", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiKeyAuthLoadsMapping() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(authorization);
        try {
            Properties properties = new Properties();
            properties.setProperty("auth", "apiKey");
            properties.setProperty("password", "encoded-key");
            properties.setProperty("pathPrefix", "/proxy");
            EsJdbcUrl config = EsJdbcUrl.parse(
                    "jdbc:es-rest://127.0.0.1:" + server.getAddress().getPort(), properties);
            try (EsTransportMetadataProvider provider = new EsTransportMetadataProvider(
                    "ds", "", new HttpTransport(config), config.endpoint())) {
                provider.listTargets();
                assertTrue(provider.loadFields(List.of("stream-a")).stream()
                        .anyMatch(f -> f.path().equals("nested.path.field")
                                && f.indexCoverage().contains("stream-a")));
            }
            assertEquals("ApiKey encoded-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(AtomicReference<String> authorization) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.equals("/proxy") || path.equals("/proxy/")) {
                body = "{\"version\":{\"number\":\"8.17.3\"}}";
            } else if (path.equals("/proxy/_cat/indices")) {
                body = "[{\"index\":\"index-a\",\"health\":\"green\",\"status\":\"open\"}]";
            } else if (path.equals("/proxy/_cat/aliases")) {
                body = "[{\"alias\":\"alias-a\",\"index\":\"index-a\"}]";
            } else if (path.equals("/proxy/_data_stream")) {
                body = "{\"data_streams\":[{\"name\":\"stream-a\"}]}";
            } else if (path.endsWith("/_mapping")) {
                body = mappingResponse();
            } else {
                body = "{}";
            }
            respond(exchange, body);
        });
        server.start();
        return server;
    }

    private static String mappingResponse() {
        return """
                {
                  "index-a": {
                    "mappings": {
                      "properties": {
                        "data": {
                          "properties": {
                            "ip": {
                              "type": "text",
                              "fields": {"keyword": {"type": "keyword"}}
                            }
                          }
                        },
                        "title": {
                          "type": "text",
                          "fields": {"raw": {"type": "keyword"}}
                        },
                        "nested": {
                          "type": "nested",
                          "properties": {
                            "path": {
                              "properties": {
                                "field": {"type": "keyword"}
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
