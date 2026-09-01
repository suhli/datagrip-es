package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataService;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsJsonPathScannerTest {
    @Test
    void scansIncompleteBoolPrefix() {
        String text = """
                {
                  "query": {
                    "bo""";
        EsJsonPathScanner.ScanResult result = EsJsonPathScanner.scan(text, 0, text.length());
        assertTrue(result.expectingKey());
        assertEquals("query", result.parentProperty());
        assertEquals("bo", result.prefix());
    }

    @Test
    void scansTermFieldKeyContext() {
        String text = """
                {
                  "query": {
                    "term": {
                      \"""";
        EsJsonPathScanner.ScanResult result = EsJsonPathScanner.scan(text, 0, text.length());
        assertTrue(result.expectingKey());
        assertTrue(result.insideString() || result.unfinishedString());
        assertEquals("term", result.parentProperty());
    }

    @Test
    void handlesEscapedQuotes() {
        String text = "{ \"a\\\"b\": \"x\", \"";
        EsJsonPathScanner.ScanResult result = EsJsonPathScanner.scan(text, 0, text.length());
        assertTrue(result.unfinishedString());
        assertTrue(result.expectingKey());
    }
}

class EsCompletionContextResolverTest {
    private final EsCompletionSchema schema = sampleSchema();

    @Test
    void resolvesClusterEndpointPrefix() {
        String text = "GET /_cl";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.ENDPOINT, ctx.expectedKind());
        assertTrue(ctx.prefix().contains("cl") || ctx.path().contains("_cl"));
    }

    @Test
    void resolvesSearchQueryParameter() {
        String text = "GET /game_logs/_search?";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.QUERY_PARAMETER, ctx.expectedKind());
        assertEquals("_search", ctx.endpoint());
    }

    @Test
    void resolvesExpandWildcardsValue() {
        String text = "GET /game_logs/_search?expand_wildcards=";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.QUERY_PARAMETER_VALUE, ctx.expectedKind());
        assertEquals("expand_wildcards", ctx.queryParameterName());
    }

    @Test
    void resolvesSearchRootBodyKey() {
        String text = "GET /game_logs/_search\n{\n  \"";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.BODY_KEY, ctx.expectedKind());
    }

    @Test
    void resolvesQueryDsl() {
        String text = "GET /game_logs/_search\n{\n  \"query\": {\n    \"";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.QUERY_DSL, ctx.expectedKind());
    }

    @Test
    void resolvesBoolChildren() {
        String text = "GET /i/_search\n{\n  \"query\": {\n    \"bool\": {\n      \"";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.BODY_KEY, ctx.expectedKind());
        assertEquals("bool", ctx.parentProperty());
    }

    @Test
    void resolvesFilterArrayQueryDsl() {
        String text = """
                GET /i/_search
                {
                  "query": {
                    "bool": {
                      "filter": [
                        {
                          \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.QUERY_DSL, ctx.expectedKind());
    }

    @Test
    void resolvesTermFieldKey() {
        String text = """
                GET /i/_search
                {
                  "query": {
                    "term": {
                      \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.FIELD_KEY, ctx.expectedKind());
    }

    @Test
    void resolvesExistsFieldValue() {
        String text = """
                GET /i/_search
                {
                  "query": {
                    "exists": {
                      "field": \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.FIELD_VALUE, ctx.expectedKind());
    }

    @Test
    void aggregationNameIsNotAggregationType() {
        String text = """
                GET /i/_search
                {
                  "aggs": {
                    \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.AGGREGATION_NAME, ctx.expectedKind());
    }

    @Test
    void aggregationTypeAfterName() {
        String text = """
                GET /i/_search
                {
                  "aggs": {
                    "test": {
                      \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.AGGREGATION_TYPE, ctx.expectedKind());
    }

    @Test
    void aggregationFieldValue() {
        String text = """
                GET /i/_search
                {
                  "aggs": {
                    "test": {
                      "terms": {
                        "field": \"""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.FIELD_VALUE, ctx.expectedKind());
    }

    @Test
    void incompleteJsonStillResolvesQueryDsl() {
        String text = """
                GET /i/_search
                {
                  "query": {
                    "bo""";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.QUERY_DSL, ctx.expectedKind());
        assertEquals("bo", ctx.prefix());
    }

    @Test
    void indexPrefixInUrl() {
        String text = "GET /game";
        var ctx = new EsCompletionContextResolver(schema).resolve(text, text.length(), "", "");
        assertEquals(EsExpectedKind.INDEX, ctx.expectedKind());
        assertEquals("game", ctx.prefix());
    }

    private static EsCompletionSchema sampleSchema() {
        return new EsCompletionSchema(
                List.of(new EsSchemaModels.Endpoint(
                        "search",
                        List.of("GET", "POST"),
                        List.of("/{index}/_search", "/_search"),
                        List.of(new EsSchemaModels.QueryParam(
                                "expand_wildcards", "enum",
                                List.of("open", "closed", "hidden", "none", "all"),
                                "", false)),
                        "_global.search.Request",
                        "", "", "", false)),
                Map.of(
                        "bool", new EsSchemaModels.DslNode(
                                "bool", "query_dsl", List.of("query"),
                                List.of("filter", "must", "must_not", "should"),
                                "object", false, List.of(), null, "Query DSL",
                                null, null, false, 100),
                        "term", new EsSchemaModels.DslNode(
                                "term", "query_dsl", List.of("query"), List.of(),
                                "field_object", true, List.of(), null, "Query DSL",
                                null, null, false, 100),
                        "exists", new EsSchemaModels.DslNode(
                                "exists", "query_dsl", List.of("query"), List.of("field"),
                                "object", true, List.of(), null, "Query DSL",
                                null, null, false, 100),
                        "exists.field", new EsSchemaModels.DslNode(
                                "field", "query_dsl_property", List.of("exists"), List.of(),
                                "field", true, List.of(), null, "", null, null, false, 80),
                        "terms", new EsSchemaModels.DslNode(
                                "terms", "bucket_aggregation", List.of("aggregation", "aggs.*"),
                                List.of("field"), "object", true, List.of(), null,
                                "Bucket aggregation", null, null, false, 100),
                        "query", new EsSchemaModels.DslNode(
                                "query", "search_body", List.of("search"), List.of(),
                                "object", false, List.of(), null, "Search body",
                                null, null, false, 110),
                        "aggs", new EsSchemaModels.DslNode(
                                "aggs", "aggregation_container", List.of("search"), List.of(),
                                "object", false, List.of(), null, "Aggregation",
                                null, null, false, 95),
                        "match.operator", new EsSchemaModels.DslNode(
                                "operator", "query_dsl_property", List.of("match"), List.of(),
                                "enum", false, List.of("and", "or"), null, "", null, null, false, 80)),
                Map.of("search", List.of("query", "aggs", "size", "sort", "_source")));
    }
}

class EsCompletionMetadataServiceTest {
    @Test
    void mergesMultiIndexFieldsAndResolvesWildcards() {
        EsCompletionMetadataSnapshot snapshot = new EsCompletionMetadataSnapshot(
                "ds",
                "9.0.0",
                List.of(
                        new EsCompletionMetadataSnapshot.IndexObject("logs-2026.08", "index"),
                        new EsCompletionMetadataSnapshot.IndexObject("logs-2026.09", "index"),
                        new EsCompletionMetadataSnapshot.IndexObject("game_logs", "index")),
                Map.of(
                        "data.ip", new EsCompletionMetadataSnapshot.FieldInfo(
                                "data.ip", Set.of("text"), Set.of("logs-2026.08"), false),
                        "data.ip.keyword", new EsCompletionMetadataSnapshot.FieldInfo(
                                "data.ip.keyword", Set.of("keyword"), Set.of("logs-2026.08", "logs-2026.09"), true),
                        "user.id", new EsCompletionMetadataSnapshot.FieldInfo(
                                "user.id", Set.of("long", "keyword"), Set.of("logs-2026.08", "game_logs"), false)),
                System.currentTimeMillis());

        List<String> resolved = EsCompletionMetadataService.resolveIndices(snapshot, List.of("logs-*"));
        assertEquals(List.of("logs-2026.08", "logs-2026.09"), resolved);
        assertTrue(snapshot.fields().containsKey("data.ip.keyword"));
        assertEquals(2, snapshot.fields().get("user.id").types().size());
    }

    @Test
    void providerRefreshIsSingleFlight() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FakeProvider provider = new FakeProvider(loads);
        AtomicInteger flag = new AtomicInteger(0);
        Runnable refresh = () -> {
            if (!flag.compareAndSet(0, 1)) {
                return;
            }
            try {
                provider.loadFields(List.of("game_logs"));
            } catch (Exception ignored) {
            }
            // keep flag set to simulate in-flight / completed single-flight gate
        };
        refresh.run();
        refresh.run();
        assertEquals(1, loads.get());
        assertFalse(provider.fields().isEmpty());
    }

    private static final class FakeProvider implements io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataProvider {
        private final AtomicInteger loads;
        private final Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields = new LinkedHashMap<>();

        FakeProvider(AtomicInteger loads) {
            this.loads = loads;
        }

        @Override
        public String datasourceId() {
            return "ds";
        }

        @Override
        public String esVersion() {
            return "9.0.0";
        }

        @Override
        public List<EsCompletionMetadataSnapshot.IndexObject> listTargets() {
            return List.of(new EsCompletionMetadataSnapshot.IndexObject("game_logs", "index"));
        }

        @Override
        public List<EsCompletionMetadataSnapshot.FieldInfo> loadFields(List<String> indexNames) {
            loads.incrementAndGet();
            List<EsCompletionMetadataSnapshot.FieldInfo> loaded = List.of(
                    new EsCompletionMetadataSnapshot.FieldInfo(
                            "data.ip", Set.of("text"), Set.copyOf(indexNames), false),
                    new EsCompletionMetadataSnapshot.FieldInfo(
                            "data.ip.keyword", Set.of("keyword"), Set.copyOf(indexNames), true));
            for (EsCompletionMetadataSnapshot.FieldInfo field : loaded) {
                fields.put(field.path(), field);
            }
            return loaded;
        }

        Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields() {
            return fields;
        }
    }
}
