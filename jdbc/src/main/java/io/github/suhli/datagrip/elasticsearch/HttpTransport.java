package io.github.suhli.datagrip.elasticsearch;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/** Apache HttpClient transport with TLS state scoped to this client instance. */
public final class HttpTransport implements Transport {
    private static final Logger LOG = Logger.getLogger(HttpTransport.class.getName());
    private final CloseableHttpClient client;
    private final Map<String, String> defaultHeaders;
    private final long connectTimeoutMillis;
    private final long defaultResponseTimeoutMillis;
    private volatile int networkTimeoutMillis;
    private volatile boolean networkTimeoutDisabled;

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
        connectTimeoutMillis = config.connectTimeout().toMillis();
        defaultResponseTimeoutMillis = config.responseTimeout().toMillis();
        networkTimeoutMillis = 0;
        networkTimeoutDisabled = false;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis))
                .setResponseTimeout(Timeout.ofMilliseconds(defaultResponseTimeoutMillis))
                .build();
        client = HttpClients.custom()
                .setConnectionManager(managerBuilder.build())
                .setDefaultRequestConfig(requestConfig)
                .build();
        defaultHeaders = authenticationHeaders(config);
    }

    @Override
    public void setNetworkTimeoutMillis(int milliseconds) {
        if (milliseconds < 0) throw new IllegalArgumentException("Network timeout must be non-negative");
        networkTimeoutMillis = milliseconds;
        networkTimeoutDisabled = milliseconds == 0;
    }

    private static Map<String, String> authenticationHeaders(EsJdbcUrl config) {
        Map<String, String> headers = new LinkedHashMap<>();
        String auth = first(config.property("auth"), config.property("authType"));
        String apiKey = first(config.property("apiKey"), config.property("apikey"));
        String user = first(config.property("user"), config.property("username"));
        String password = config.property("password");
        if ("none".equalsIgnoreCase(auth)) {
            // Explicitly disabled.
        } else if ("apiKey".equalsIgnoreCase(auth)) {
            String secret = first(apiKey, password);
            if (secret != null && !secret.isBlank()) headers.put("Authorization", "ApiKey " + secret);
        } else if (apiKey != null) {
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
        return execute(request, null);
    }

    @Override
    public Response execute(Request request, ExecuteOptions options) throws IOException {
        long started = System.nanoTime();
        HttpClientContext context = HttpClientContext.create();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis))
                .setResponseTimeout(resolveResponseTimeout(options))
                .build();
        context.setRequestConfig(requestConfig);

        HttpUriRequestBase httpRequest = new HttpUriRequestBase(request.method(), request.uri());
        defaultHeaders.forEach(httpRequest::addHeader);
        request.headers().forEach(httpRequest::setHeader);
        if (request.body() != null) {
            String contentType = request.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(ContentType.APPLICATION_JSON.getMimeType());
            httpRequest.setEntity(new StringEntity(request.body(), ContentType.parse(contentType)));
        }

        Transport.Cancellation cancellation = options == null ? null : options.cancellation();
        if (cancellation instanceof Transport.RequestCancellation token) {
            // Prefer real request cancel; interrupt is only a secondary assist.
            token.bind(httpRequest::cancel);
            token.bindExecution(Thread.currentThread());
        }

        try {
            Response result = client.execute(httpRequest, context, response -> {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
                }
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                return new Response(response.getCode(), Map.copyOf(headers), body);
            });
            if (LOG.isLoggable(Level.FINE)) {
                long durationMillis = (System.nanoTime() - started) / 1_000_000L;
                LOG.fine(() -> request.method() + " " + request.uri().getRawPath()
                        + " -> HTTP " + result.status() + " in " + durationMillis + " ms");
            }
            return result;
        } finally {
            // Do not leave a stale interrupt flag for the caller's next work.
            if (cancellation != null && cancellation.isCancelled()) {
                Thread.interrupted();
            }
        }
    }

    private Timeout resolveResponseTimeout(ExecuteOptions options) {
        if (options != null && options.timeoutMillis() > 0) {
            return Timeout.ofMilliseconds(options.timeoutMillis());
        }
        if (networkTimeoutDisabled) {
            return Timeout.ZERO_MILLISECONDS;
        }
        if (networkTimeoutMillis > 0) {
            return Timeout.ofMilliseconds(networkTimeoutMillis);
        }
        return Timeout.ofMilliseconds(defaultResponseTimeoutMillis);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
