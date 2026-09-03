package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EsEndpointResolverTest {
    private final EsCompletionSchema schema = new EsCompletionSchema(List.of(
            endpoint("get-settings", "GET", "/{index}/_settings"),
            endpoint("put-settings", "PUT", "/{index}/_settings"),
            endpoint("get-doc", "GET", "/{index}/_doc/{id}"),
            endpoint("snapshot", "GET", "/_snapshot/{repository}/{snapshot}"),
            endpoint("pipeline", "PUT", "/_ingest/pipeline/{id}")
    ), Map.of(), Map.of());

    @Test
    void resolvesByMethodAndFullTemplatePath() {
        assertEquals("get-settings",
                schema.resolveEndpoint("GET", "/foo/_settings?flat_settings=true").endpoint().name());
        assertEquals("put-settings",
                schema.resolveEndpoint("PUT", "/foo/_settings").endpoint().name());
        assertEquals(Map.of("index", "foo", "id", "123"),
                schema.resolveEndpoint("GET", "/foo/_doc/123?pretty=true").pathParameters());
        assertEquals(Map.of("repository", "repo", "snapshot", "nightly"),
                schema.resolveEndpoint("GET", "/_snapshot/repo/nightly").pathParameters());
        assertEquals("test",
                schema.resolveEndpoint("PUT", "/_ingest/pipeline/test").pathParameters().get("id"));
        assertNull(schema.resolveEndpoint("POST", "/foo/_settings"));
    }

    @Test
    void partialFallbackRemainsMethodAwareAndRejectsAmbiguity() {
        assertEquals("get-settings",
                schema.findEndpointByPartialPath("GET", "/foo/_sett", "_sett").name());
        assertEquals("put-settings",
                schema.findEndpointByPartialPath("PUT", "/foo/_sett", "_sett").name());
        assertNull(schema.findEndpointByPartialPath("POST", "/foo/_sett", "_sett"));
        assertNull(schema.findEndpointByPartialPath("", "/foo/_sett", "_sett"));
    }

    private static EsSchemaModels.Endpoint endpoint(String name, String method, String path) {
        return new EsSchemaModels.Endpoint(
                name, List.of(method), List.of(path), List.of(), null,
                "", null, null, false);
    }
}
