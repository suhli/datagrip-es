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
                property("user", false, "Basic authentication user"),
                property("password", false, "Basic authentication password"),
                property("apiKey", false, "Elasticsearch API key"),
                property("ssl", false, "Use HTTPS"),
                property("verifyTls", false, "Verify server certificate and hostname"),
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

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("io.github.suhli.datagrip.elasticsearch"); }
}
