package io.github.suhli.datagrip.elasticsearch;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpTransportTlsTest {
    @Test
    void insecureTlsIsScopedToOneTransport() throws Exception {
        HttpsServer server = serverWithWrongHostSelfSignedCertificate();
        server.start();
        try {
            int port = server.getAddress().getPort();
            EsJdbcUrl insecureConfig = EsJdbcUrl.parse(
                    "jdbc:es-rest:https://localhost:" + port, properties("verifyTls", "false"));
            try (HttpTransport insecure = new HttpTransport(insecureConfig)) {
                Transport.Response response = insecure.execute(new Transport.Request(
                        "GET", insecureConfig.endpoint(), Map.of(), null));
                assertEquals(200, response.status());
            }

            EsJdbcUrl strictConfig = EsJdbcUrl.parse(
                    "jdbc:es-rest:https://localhost:" + port, new Properties());
            try (HttpTransport strict = new HttpTransport(strictConfig)) {
                assertThrows(Exception.class, () -> strict.execute(new Transport.Request(
                        "GET", strictConfig.endpoint(), Map.of(), null)));
            }
        } finally {
            server.stop(0);
        }
    }

    private static Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }

    private static HttpsServer serverWithWrongHostSelfSignedCertificate() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        var name = new X500Name("CN=wrong-host.invalid");
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)),
                name, keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(new BouncyCastleProvider())
                .build(keyPair.getPrivate());
        var certificate = new JcaX509CertificateConverter()
                .setProvider(new BouncyCastleProvider())
                .getCertificate(builder.build(signer));

        char[] password = "test-only".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null);
        keyStore.setKeyEntry("server", keyPair.getPrivate(), password,
                new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, new SecureRandom());

        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"cluster_name":"test","cluster_uuid":"uuid",
                     "version":{"number":"9.0.0"},"tagline":"You Know, for Search"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
