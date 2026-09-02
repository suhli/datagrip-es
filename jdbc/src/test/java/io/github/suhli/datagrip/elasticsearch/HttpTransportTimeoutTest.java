package io.github.suhli.datagrip.elasticsearch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpTransportTimeoutTest {
    @Test
    void explicitZeroNetworkTimeoutDisablesConfiguredResponseTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Properties properties = new Properties();
            properties.setProperty("responseTimeout", "50");
            EsJdbcUrl config = EsJdbcUrl.parse(
                    "jdbc:es-rest://127.0.0.1:" + server.getAddress().getPort(), properties);
            try (HttpTransport transport = new HttpTransport(config)) {
                transport.setNetworkTimeoutMillis(0);
                Transport.Response response = transport.execute(
                        new Transport.Request("GET", config.endpoint(), Map.of(), null));
                assertEquals(200, response.status());
            }
        } finally {
            server.stop(0);
        }
    }
}
