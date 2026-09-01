package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCaretLocation;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCompletionContext;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Candidate selection without IntelliJ CompletionResultSet. */
class EsCompletionCandidateTest {
    @Test
    void searchBodyAndQueryDslCandidatesComeFromSchema() {
        EsCompletionSchema schema = schema();
        List<String> body = new ArrayList<>();
        for (String key : schema.bodyRootsForEndpoint("search")) {
            body.add(key);
        }
        assertTrue(body.contains("query"));
        assertTrue(body.contains("aggs"));

        List<String> dsl = schema.queryDslKeys().stream().map(EsSchemaModels.DslNode::key).toList();
        assertTrue(dsl.contains("bool"));
        assertTrue(dsl.contains("term"));
    }

    @Test
    void fieldCandidatesIncludeMultiFields() {
        EsCompletionMetadataSnapshot snapshot = new EsCompletionMetadataSnapshot(
                "ds", "9.0.0",
                List.of(new EsCompletionMetadataSnapshot.IndexObject("game_logs", "index")),
                Map.of(
                        "data.ip", new EsCompletionMetadataSnapshot.FieldInfo(
                                "data.ip", Set.of("text"), Set.of("game_logs"), false),
                        "data.ip.keyword", new EsCompletionMetadataSnapshot.FieldInfo(
                                "data.ip.keyword", Set.of("keyword"), Set.of("game_logs"), true),
                        "timestamp", new EsCompletionMetadataSnapshot.FieldInfo(
                                "timestamp", Set.of("date"), Set.of("game_logs"), false)),
                System.currentTimeMillis());
        assertTrue(snapshot.fields().containsKey("data.ip"));
        assertTrue(snapshot.fields().containsKey("data.ip.keyword"));
    }

    @Test
    void snippetTemplatesExistForCommonKeys() {
        EsCompletionSchema schema = schema();
        assertTrue(schema.findKey("term").snippet().contains("$FIELD$"));
        assertTrue(schema.findKey("range").snippet().contains("gte"));
        assertTrue(schema.findKey("bool").snippet().contains("bool"));
        assertTrue(schema.findKey("terms").snippet().contains("field"));
    }

    @Test
    void insertHandlerDoesNotDoubleQuoteWhenInsideString() {
        EsCompletionContext ctx = EsCompletionContext.builder()
                .location(EsCaretLocation.BODY)
                .expectedKind(EsExpectedKind.FIELD_KEY)
                .insideString(true)
                .prefix("data.")
                .build();
        var element = EsLookupFactory.field(
                new EsCompletionMetadataSnapshot.FieldInfo(
                        "data.ip.keyword", Set.of("keyword"), Set.of("game_logs"), true),
                ctx);
        assertFalse(element.getLookupString().contains("\""));
        assertTrue(element.getLookupString().equals("data.ip.keyword"));
    }

    @Test
    void fieldKeyPairUsesQuotedKeyAndEmptyValue() {
        EsCompletionContext ctx = EsCompletionContext.builder()
                .location(EsCaretLocation.BODY)
                .expectedKind(EsExpectedKind.FIELD_KEY)
                .insideString(false)
                .parentProperty("term")
                .build();
        var element = EsLookupFactory.field(
                new EsCompletionMetadataSnapshot.FieldInfo(
                        "user.id", Set.of("keyword"), Set.of("game_logs"), false),
                ctx);
        assertEquals("user.id", element.getLookupString());
    }

    private static EsCompletionSchema schema() {
        return new EsCompletionSchema(
                List.of(),
                Map.of(
                        "query", new EsSchemaModels.DslNode(
                                "query", "search_body", List.of("search"), List.of(), "object",
                                false, List.of(), "\"query\": {\n  $END$\n}", "Search body",
                                null, null, false, 110),
                        "aggs", new EsSchemaModels.DslNode(
                                "aggs", "search_body", List.of("search"), List.of(), "object",
                                false, List.of(), null, "Search body", null, null, false, 100),
                        "bool", new EsSchemaModels.DslNode(
                                "bool", "query_dsl", List.of("query"), List.of("filter"),
                                "object", false, List.of(), "\"bool\": {\n  $END$\n}", "Query DSL",
                                null, null, false, 120),
                        "term", new EsSchemaModels.DslNode(
                                "term", "query_dsl", List.of("query"), List.of(), "field_object",
                                true, List.of(), "\"term\": {\n  \"$FIELD$\": \"$VALUE$\"\n}",
                                "Query DSL", null, null, false, 120),
                        "range", new EsSchemaModels.DslNode(
                                "range", "query_dsl", List.of("query"), List.of(), "field_object",
                                true, List.of(),
                                "\"range\": {\n  \"$FIELD$\": {\n    \"gte\": \"$VALUE$\",\n    \"lte\": \"$VALUE$\"\n  }\n}",
                                "Query DSL", null, null, false, 118),
                        "terms", new EsSchemaModels.DslNode(
                                "terms", "bucket_aggregation", List.of("aggs.*"), List.of("field"),
                                "object", true, List.of(),
                                "\"terms\": {\n  \"field\": \"$FIELD$\"\n}",
                                "Bucket aggregation", null, null, false, 120)),
                Map.of("search", List.of("query", "aggs", "size", "sort")));
    }
}
