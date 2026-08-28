package io.github.suhli.datagrip.elasticsearch;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

public interface Transport extends Closeable {
    Response execute(Request request) throws IOException;

    record Request(String method, URI uri, Map<String, String> headers, String body) {
        public Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    record Response(int status, Map<String, List<String>> headers, String body) {
        public boolean successful() { return status >= 200 && status < 300; }
    }
}
