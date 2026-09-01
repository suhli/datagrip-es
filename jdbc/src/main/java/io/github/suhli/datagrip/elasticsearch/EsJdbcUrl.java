package io.github.suhli.datagrip.elasticsearch;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Parsed JDBC endpoint and connection options. */
public final class EsJdbcUrl {
    public static final String PREFIX = "jdbc:es-rest:";

    private final URI endpoint;
    private final boolean verifyTls;
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Properties properties;

    private EsJdbcUrl(URI endpoint, boolean verifyTls, Duration connectTimeout,
                      Duration responseTimeout, Properties properties) {
        this.endpoint = endpoint;
        this.verifyTls = verifyTls;
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.properties = properties;
    }

    public static EsJdbcUrl parse(String url, Properties supplied) throws SQLException {
        if (url == null || !url.startsWith(PREFIX)) {
            throw new SQLException("Unsupported JDBC URL; expected " + PREFIX, "08001");
        }
        String rest = url.substring(PREFIX.length());
        boolean explicitHttps = rest.startsWith("https://");
        if (!explicitHttps && !rest.startsWith("//")) {
            throw new SQLException("JDBC URL must contain //host or https://host", "08001");
        }
        String uriText = explicitHttps ? rest : "http:" + rest;
        URI raw;
        try {
            raw = URI.create(uriText);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid JDBC URL", "08001", e);
        }
        if (raw.getHost() == null || raw.getUserInfo() != null) {
            throw new SQLException("JDBC URL must have a host and must not contain user-info", "08001");
        }

        Map<String, String> values = parseQuery(raw.getRawQuery());
        if (values.keySet().stream().anyMatch(EsJdbcUrl::isSecretKey)) {
            throw new SQLException("Credentials must be supplied through connection Properties, not the JDBC URL",
                    "08001");
        }
        if (supplied != null) {
            supplied.stringPropertyNames().forEach(k -> values.put(k, supplied.getProperty(k)));
        }
        validateAuthMode(values);
        if (values.containsKey("requestTimeout") && !values.containsKey("responseTimeout")) {
            values.put("responseTimeout", values.get("requestTimeout"));
        }
        boolean ssl = bool(values, "ssl", explicitHttps);
        boolean verifyTls = bool(values, "verifyTls", true);
        int port = raw.getPort() >= 0 ? raw.getPort() : (ssl ? 443 : 9200);
        String path = values.getOrDefault("pathPrefix", raw.getRawPath());
        if (path == null || path.isBlank() || path.equals("/")) path = "";
        else path = "/" + trimSlashes(path);
        try {
            String host = raw.getHost();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            URI endpoint = new URI(ssl ? "https" : "http", null, host, port, path, null, null);
            Properties options = new Properties();
            values.forEach(options::setProperty);
            return new EsJdbcUrl(endpoint, verifyTls,
                    timeout(values, "connectTimeout", "connectTimeoutMs", 10_000),
                    timeout(values, "responseTimeout", "socketTimeout", 60_000), options);
        } catch (Exception e) {
            throw new SQLException("Invalid endpoint in JDBC URL", "08001", e);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return result;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return "password".equals(lower) || "apikey".equals(lower) || "authorization".equals(lower)
                || "token".equals(lower) || lower.startsWith("header.") || "headers".equals(lower);
    }

    private static void validateAuthMode(Map<String, String> values) throws SQLException {
        String auth = values.containsKey("auth") ? values.get("auth") : values.get("authType");
        if (auth != null && !auth.equalsIgnoreCase("none") && !auth.equalsIgnoreCase("basic")
                && !auth.equalsIgnoreCase("apiKey")) {
            throw new SQLException("auth must be none, basic, or apiKey", "08001");
        }
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) throws SQLException {
        String value = values.get(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new SQLException(key + " must be true or false", "08001");
    }

    private static Duration timeout(Map<String, String> values, String key, String alias, long fallback)
            throws SQLException {
        String value = values.containsKey(key) ? values.get(key) : values.get(alias);
        if (value == null) return Duration.ofMillis(fallback);
        try {
            long millis = Long.parseLong(value);
            if (millis < 0) throw new NumberFormatException();
            return Duration.ofMillis(millis);
        } catch (NumberFormatException e) {
            throw new SQLException(key + " must be a non-negative millisecond value", "08001", e);
        }
    }

    private static String trimSlashes(String path) {
        int start = 0, end = path.length();
        while (start < end && path.charAt(start) == '/') start++;
        while (end > start && path.charAt(end - 1) == '/') end--;
        return path.substring(start, end);
    }

    public URI endpoint() { return endpoint; }
    public boolean verifyTls() { return verifyTls; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration responseTimeout() { return responseTimeout; }
    public String property(String name) { return properties.getProperty(name); }
    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public String jdbcUrl() {
        String endpointText = endpoint.toASCIIString();
        if (endpointText.startsWith("http://")) {
            return PREFIX + endpointText.substring("http:".length());
        }
        return PREFIX + endpointText;
    }

    @Override
    public String toString() {
        return "EsJdbcUrl{endpoint=" + endpoint + ", verifyTls=" + verifyTls
                + ", connectTimeout=" + connectTimeout + ", responseTimeout=" + responseTimeout + '}';
    }
}
