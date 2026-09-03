package io.github.suhli.datagrip.elasticsearch;

import java.net.URI;
import java.util.Objects;

/** URI joining that preserves an Elasticsearch endpoint path prefix. */
public final class EsUris {
    private EsUris() {}

    public static URI resolve(URI endpoint, String requestPath) {
        Objects.requireNonNull(endpoint, "endpoint");
        String request = requestPath == null || requestPath.isBlank() ? "/" : requestPath;
        String basePath = endpoint.getRawPath();
        if (basePath == null || basePath.equals("/")) basePath = "";
        while (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        while (request.startsWith("/")) request = request.substring(1);

        String joinedPath = basePath + "/" + request;
        String query = null;
        int queryStart = joinedPath.indexOf('?');
        if (queryStart >= 0) {
            query = joinedPath.substring(queryStart + 1);
            joinedPath = joinedPath.substring(0, queryStart);
        }
        try {
            String value = endpoint.getScheme() + "://" + endpoint.getRawAuthority() + joinedPath;
            if (query != null) value += "?" + query;
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Elasticsearch request path: " + requestPath, e);
        }
    }
}
