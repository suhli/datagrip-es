package io.github.suhli.datagrip.elasticsearch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpTransportAuthenticationTest {
    @Test
    void sendsBasicAndApiKeyWithoutPuttingSecretsInUrl() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String url = "jdbc:es-rest://localhost:" + server.getAddress().getPort();
            Properties basic = new Properties();
            basic.setProperty("auth", "basic");
            basic.setProperty("user", "alice");
            basic.setProperty("password", "secret");
            try (HttpTransport transport = new HttpTransport(EsJdbcUrl.parse(url, basic))) {
                transport.execute(new Transport.Request("GET",
                        EsJdbcUrl.parse(url, basic).endpoint(), Map.of(), null));
            }
            assertEquals("Basic YWxpY2U6c2VjcmV0", authorization.get());

            Properties apiKey = new Properties();
            apiKey.setProperty("auth", "apiKey");
            apiKey.setProperty("password", "encoded-key");
            apiKey.setProperty("header.X-Tenant", "private");
            EsJdbcUrl config = EsJdbcUrl.parse(url, apiKey);
            try (HttpTransport transport = new HttpTransport(config)) {
                transport.execute(new Transport.Request("GET", config.endpoint(), Map.of(), null));
            }
            assertEquals("ApiKey encoded-key", authorization.get());
            assertEquals("private", customHeader.get());
            assertEquals(-1, config.jdbcUrl().indexOf("encoded-key"));
        } finally {
            server.stop(0);
        }
    }
}
