package io.github.suhli.datagrip.elasticsearch;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Apache HttpClient transport with TLS state scoped to this client instance. */
public final class HttpTransport implements Transport {
    private final CloseableHttpClient client;
    private final Map<String, String> defaultHeaders;

    public HttpTransport(EsJdbcUrl config) throws GeneralSecurityException {
        var managerBuilder = PoolingHttpClientConnectionManagerBuilder.create();
        if ("https".equals(config.endpoint().getScheme()) && !config.verifyTls()) {
            SSLContext context = SSLContexts.custom()
                    .loadTrustMaterial((chain, authType) -> true)
                    .build();
            managerBuilder.setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(context)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .build());
        }
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeout().toMillis()))
                .build();
        client = HttpClients.custom()
                .setConnectionManager(managerBuilder.build())
                .setDefaultRequestConfig(requestConfig)
                .build();
        defaultHeaders = authenticationHeaders(config);
    }

    private static Map<String, String> authenticationHeaders(EsJdbcUrl config) {
        Map<String, String> headers = new LinkedHashMap<>();
        String apiKey = first(config.property("apiKey"), config.property("apikey"));
        String user = first(config.property("user"), config.property("username"));
        String password = config.property("password");
        if (apiKey != null) {
            headers.put("Authorization", "ApiKey " + apiKey);
        } else if (user != null) {
            String token = Base64.getEncoder().encodeToString(
                    (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + token);
        }
        String custom = config.property("headers");
        if (custom != null) {
            for (String entry : custom.split("[\\r\\n;]+")) {
                int split = entry.indexOf(':');
                if (split > 0) headers.put(entry.substring(0, split).trim(), entry.substring(split + 1).trim());
            }
        }
        config.properties().stringPropertyNames().stream()
                .filter(k -> k.startsWith("header."))
                .forEach(k -> headers.put(k.substring(7), config.property(k)));
        return Map.copyOf(headers);
    }

    private static String first(String first, String second) {
        return first != null ? first : second;
    }

    @Override
    public Response execute(Request request) throws IOException {
        var builder = org.apache.hc.client5.http.classic.methods.ClassicRequestBuilder
                .create(request.method()).setUri(request.uri());
        defaultHeaders.forEach(builder::addHeader);
        request.headers().forEach(builder::setHeader);
        if (request.body() != null) {
            builder.setEntity(new StringEntity(request.body(), ContentType.APPLICATION_JSON));
        }
        return client.execute(builder.build(), response -> {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Header header : response.getHeaders()) {
                headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
            }
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
            return new Response(response.getCode(), Map.copyOf(headers), body);
        });
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
