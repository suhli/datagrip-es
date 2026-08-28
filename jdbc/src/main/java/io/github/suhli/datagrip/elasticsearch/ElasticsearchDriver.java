package io.github.suhli.datagrip.elasticsearch;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

public final class ElasticsearchDriver implements Driver {
    static {
        try {
            DriverManager.registerDriver(new ElasticsearchDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        return JdbcProxies.open(EsJdbcUrl.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(EsJdbcUrl.PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[] {
                choice("auth", "none", "Authentication mode", "none", "basic", "apiKey"),
                property("user", false, "Basic authentication user"),
                property("password", false, "Basic password, or API key when auth=apiKey"),
                property("apiKey", false, "Elasticsearch API key"),
                choice("ssl", "false", "Use HTTPS", "false", "true"),
                choice("verifyTls", "true", "Verify server certificate and hostname", "true", "false"),
                property("pathPrefix", false, "Reverse-proxy path prefix"),
                property("connectTimeout", false, "Connect timeout in milliseconds"),
                property("responseTimeout", false, "Response timeout in milliseconds"),
                property("headers", false, "Additional semicolon-separated HTTP headers")
        };
    }

    private static DriverPropertyInfo property(String name, boolean required, String description) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, null);
        property.required = required;
        property.description = description;
        return property;
    }

    private static DriverPropertyInfo choice(String name, String value, String description, String... choices) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, value);
        property.description = description;
        property.choices = choices;
        return property;
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("io.github.suhli.datagrip.elasticsearch"); }
}
