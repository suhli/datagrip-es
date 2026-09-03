package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.OffsetMap;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataService;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchemaLoader;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestFileType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** IntelliJ PSI fixture coverage for .esrest completion lookup generation. */
public class EsRestCompletionFixtureTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertFalse("Completion schema must be packaged with the plugin",
                EsCompletionSchemaLoader.get().endpoints().isEmpty());
    }

    public void testEndpointCompletion() {
        List<String> lookup = complete("GET /_cl<caret>");
        assertTrue("Expected cluster endpoint, got: " + lookup,
                lookup.stream().anyMatch(s -> s.contains("cluster")));
    }

    public void testSearchBodyCompletion() {
        List<String> lookup = complete("""
                GET /game_logs/_search
                {
                  "<caret>"
                }
                """);
        assertTrue("Expected search body keys, got: " + lookup,
                lookup.stream().anyMatch("query"::equals));
        assertTrue(lookup.stream().anyMatch(s -> s.equals("aggs") || s.equals("aggregations")));
    }

    public void testQueryInsertionFormatsObjectAndPlacesCaretInside() {
        insertCompletion("GET /game_logs/_search\n{<caret>}", "query");
        myFixture.checkResult("GET /game_logs/_search\n{\n  \"query\" : {\n    <caret>\n  }\n}");
    }

    public void testQueryInsertionReusesPairedQuotes() {
        insertCompletion("GET /game_logs/_search\n{\"<caret>\"}", "query");
        myFixture.checkResult("GET /game_logs/_search\n{\n  \"query\" : {\n    <caret>\n  }\n}");
    }

    public void testQueryInsertionClosesUnfinishedKeyQuote() {
        insertCompletion("GET /game_logs/_search\n{\"qu<caret>}", "query");
        myFixture.checkResult("GET /game_logs/_search\n{\n  \"query\" : {\n    <caret>\n  }\n}");
    }

    public void testNestedBoolInsertionFormatsObjectAndPlacesCaretInside() {
        insertCompletion("GET /game_logs/_search\n{\"query\":{<caret>}}", "bool");
        myFixture.checkResult("GET /game_logs/_search\n{\n  \"query\" : {\n    \"bool\" : {\n      <caret>\n    }\n  }\n}");
    }

    public void testTermInsertionKeepsCaretInFieldPlaceholder() {
        insertCompletion("GET /game_logs/_search\n{\"query\":{<caret>}}", "term");
        myFixture.checkResult("GET /game_logs/_search\n{\n  \"query\" : {\n    \"term\" : {\n      \"<caret>\" : \"\"\n    }\n  }\n}");
    }

    public void testQueryInsertionKeepsCaretInSecondRequest() {
        insertCompletion("GET /first/_search\n{\"query\":{\"term\":{\"\":\"\"}}}\n\nGET /second/_search\n{<caret>}", "query");
        String text = myFixture.getEditor().getDocument().getText();
        int secondQuery = text.lastIndexOf("\"query\"");
        assertTrue(myFixture.getCaretOffset() > secondQuery);
        assertEquals("{\n    \n  }\n}", text.substring(text.indexOf('{', secondQuery)));
        assertEquals(text.indexOf("\n  }", secondQuery), myFixture.getCaretOffset());
    }

    private void insertCompletion(String text, String key) {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, text);
        int tail = myFixture.getCaretOffset();
        var schema = EsCompletionSchemaLoader.get();
        var completion = new EsCompletionContextResolver(schema).resolve(file, tail, "", "");
        var selected = EsRestCompletionContributor.lookupElementsForTest(file, tail, "").stream()
                .filter(item -> item.getLookupString().equals(key))
                .findFirst().orElseThrow(() -> new AssertionError("Missing completion: " + key));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var editor = myFixture.getEditor();
            var document = editor.getDocument();
            int start = tail - completion.prefix().length();
            // Completion replaces the typed prefix before invoking the lookup's insert handler.
            document.replaceString(start, tail, selected.getLookupString());
            int insertedTail = start + selected.getLookupString().length();
            editor.getCaretModel().moveToOffset(insertedTail);
            OffsetMap offsets = new OffsetMap(document);
            offsets.addOffset(CompletionInitializationContext.START_OFFSET, start);
            offsets.addOffset(CompletionInitializationContext.SELECTION_END_OFFSET, insertedTail);
            offsets.addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, insertedTail);
            InsertionContext insertion = new InsertionContext(
                    offsets, '\n', new LookupElement[]{selected}, file, editor, false);
            selected.handleInsert(insertion);
        });
    }

    public void testQueryDslCompletion() {
        List<String> lookup = complete("""
                GET /game_logs/_search
                {
                  "query": {
                    "<caret>"
                  }
                }
                """);
        assertTrue("Expected query DSL keys, got: " + lookup, lookup.contains("bool"));
        assertTrue(lookup.contains("term") || lookup.contains("match"));
    }

    public void testMultiMatchRootCompletesPropertiesNotFieldKeys() {
        List<String> lookup = complete("""
                GET /game_logs/_search
                {
                  "query": {
                    "multi_match": {
                      "<caret>"
                    }
                  }
                }
                """);
        assertTrue("Expected multi_match properties, got: " + lookup, lookup.contains("query"));
        assertTrue(lookup.contains("fields"));
    }

    public void testBrokenPsiUsesCurrentRequestAndIndexScopedFields() {
        EsCompletionMetadataService.getInstance(getProject()).putSnapshot(new EsCompletionMetadataSnapshot(
                "test-ds",
                "8.17.0",
                List.of(
                        new EsCompletionMetadataSnapshot.IndexObject("index-a", "index"),
                        new EsCompletionMetadataSnapshot.IndexObject("index-b", "index")),
                Map.of(
                        "field_a", field("field_a", "index-a", false),
                        "field_b", field("field_b", "index-b", false),
                        "common", new EsCompletionMetadataSnapshot.FieldInfo(
                                "common", Set.of("keyword"), Set.of("index-a", "index-b"), false),
                        "data.ip", field("data.ip", "index-b", false),
                        "data.ip.keyword", field("data.ip.keyword", "index-b", true),
                        "title.raw", field("title.raw", "index-b", true),
                        "nested.path.field", field("nested.path.field", "index-b", false)),
                System.currentTimeMillis()));

        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, """
                GET /index-a/_search
                {
                  "query": {
                    "term": {
                      "field_a": "x"
                    }
                  }
                }

                POST /index-b/_search
                {
                  "query": {
                    "bool": {
                      "must": [
                        {
                          "term": {
                            "<caret>
                """);
        List<String> lookup = EsRestCompletionContributor.lookupStringsForTest(
                file, myFixture.getCaretOffset(), "test-ds");
        assertTrue(lookup.contains("field_b"));
        assertTrue(lookup.contains("common"));
        assertTrue(lookup.contains("data.ip"));
        assertTrue(lookup.contains("data.ip.keyword"));
        assertTrue(lookup.contains("title.raw"));
        assertTrue(lookup.contains("nested.path.field"));
        assertFalse(lookup.contains("field_a"));
    }

    public void testIncompletePropertyDoesNotCrossIntoPreviousRequest() {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, """
                GET /foo/_search
                {
                  "query": {
                    "bool": {
                      "must": []
                    }
                  }
                }

                POST /bar/_search
                {
                  "query": {
                    "boo<caret>
                """);
        List<String> lookup = EsRestCompletionContributor.lookupStringsForTest(
                file, myFixture.getCaretOffset());
        assertTrue(lookup.contains("bool"));
    }

    public void testWhitespaceAndUrlTypingStayInSecondRequest() {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, """
                GET /first/_search
                {"query":{"match_all":{}}}

                POST /second/_sea<caret>

                """);
        var context = new EsCompletionContextResolver(EsCompletionSchemaLoader.get())
                .resolve(file, myFixture.getCaretOffset(), "", "");
        assertEquals(List.of("second"), context.indices());
        assertTrue(context.path().contains("second"));
    }

    public void testMalformedNestedObjectStopsAtNextMethodToken() {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, """
                GET /first/_search
                {
                  "query": {
                    "bool": {

                GET /second/_search
                {
                  "query": {
                    "term": {
                      "<caret>"
                    }
                  }
                }
                """);
        var context = new EsCompletionContextResolver(EsCompletionSchemaLoader.get())
                .resolve(file, myFixture.getCaretOffset(), "", "");
        assertEquals("/second/_search", context.path());
        assertEquals(List.of("second"), context.indices());
    }

    public void testCreateIndexBodyDoesNotOfferSearchKeys() {
        List<String> lookup = complete("""
                PUT /my-index
                {
                  "<caret>"
                }
                """);
        assertTrue("Expected generated create-index schema, got: " + lookup,
                lookup.contains("settings") || lookup.contains("mappings") || lookup.contains("aliases"));
        assertFalse("Non-search endpoint must not offer SearchRequest keys: " + lookup,
                lookup.contains("query") || lookup.contains("aggs") || lookup.contains("size"));
    }

    public void testRequestScopedSchemasDoNotCrossContaminateAndNavigateDeepPaths() {
        EsCompletionSchema original = EsCompletionSchemaLoader.get();
        EsCompletionSchema scoped = new EsCompletionSchema(
                List.of(endpoint("api-a", "api.a.Request", "/api-a"),
                        endpoint("api-b", "api.b.Request", "/api-b")),
                Map.of(),
                Map.of("api-a", List.of("settings", "query", "objects", "metadata", "track_total_hits"),
                        "api-b", List.of("settings", "query")),
                Map.of(
                        "api.a.Request", Map.of(
                                "settings", property("settings", "A.Settings", "object"),
                                "query", property("query", "A.Query", "object"),
                                "objects", property("objects", "A.Item", "array<object>"),
                                "metadata", dictionaryProperty("metadata", "A.Metadata"),
                                "track_total_hits",
                                property("track_total_hits", null, "boolean|number")),
                        "api.b.Request", Map.of(
                                "settings", property("settings", "B.Settings", "object"),
                                "query", property("query", "B.Query", "object"))),
                Map.of(
                        "A.Settings", Map.of("foo", property("foo", "A.Foo", "object")),
                        "A.Foo", Map.of("child", property("child", "A.Child", "object")),
                        "A.Child", Map.of(
                                "grandchild", property("grandchild", "A.Grandchild", "object")),
                        "A.Grandchild", Map.of("leaf", property("leaf", null, "string")),
                        "A.Query", Map.of("script", property("script", null, "string")),
                        "A.Item", Map.of("object_leaf", property("object_leaf", null, "string")),
                        "A.Metadata", Map.of("map_leaf", property("map_leaf", null, "string")),
                        "B.Settings", Map.of("bar", property("bar", null, "string")),
                        "B.Query", Map.of("source", property("source", null, "string"))));
        EsCompletionSchemaLoader.resetForTests(scoped);
        try {
            List<String> aSettings = complete("""
                    PUT /api-a
                    {"settings":{"<caret>"}}
                    """);
            assertTrue(aSettings.contains("foo"));
            assertFalse(aSettings.contains("bar"));

            List<String> bSettings = complete("""
                    PUT /api-b
                    {"settings":{"<caret>"}}
                    """);
            assertTrue(bSettings.contains("bar"));
            assertFalse(bSettings.contains("foo"));

            List<String> aQuery = complete("""
                    PUT /api-a
                    {"query":{"<caret>"}}
                    """);
            assertTrue(aQuery.contains("script"));
            assertFalse(aQuery.contains("source"));

            List<String> deep = complete("""
                    PUT /api-a
                    {"settings":{"foo":{"child":{"grandchild":{"<caret>"}}}}}
                    """);
            assertTrue(deep.contains("leaf"));

            assertTrue(complete("""
                    PUT /api-a
                    {"objects":[{"<caret>"}]}
                    """).contains("object_leaf"));
            assertTrue(complete("""
                    PUT /api-a
                    {"metadata":{"custom":{"<caret>"}}}
                    """).contains("map_leaf"));
            List<String> union = complete("""
                    PUT /api-a
                    {"track_total_hits": <caret>}
                    """);
            assertTrue(union.contains("true"));
            assertTrue(union.contains("false"));
        } finally {
            EsCompletionSchemaLoader.resetForTests(original);
        }
    }

    private static EsSchemaModels.Endpoint endpoint(String name, String requestType, String path) {
        return new EsSchemaModels.Endpoint(
                name, List.of("PUT"), List.of(path), List.of(), requestType,
                "", null, null, false);
    }

    private static EsSchemaModels.GenericProperty property(
            String key, String childType, String valueType) {
        return new EsSchemaModels.GenericProperty(
                new EsSchemaModels.DslNode(
                        key, "request_body", List.of(), List.of(), valueType, false,
                        List.of(), null, "request body", null, null, false, 100),
                childType == null ? List.of() : List.of(childType),
                List.of());
    }

    private static EsSchemaModels.GenericProperty dictionaryProperty(
            String key, String valueType) {
        return new EsSchemaModels.GenericProperty(
                new EsSchemaModels.DslNode(
                        key, "request_body", List.of(), List.of(), "dictionary<object>", false,
                        List.of(), null, "request body", null, null, false, 100),
                List.of(), List.of(valueType));
    }

    private List<String> complete(String text) {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, text);
        return EsRestCompletionContributor.lookupStringsForTest(file, myFixture.getCaretOffset());
    }

    private static EsCompletionMetadataSnapshot.FieldInfo field(
            String path, String index, boolean multiField) {
        return new EsCompletionMetadataSnapshot.FieldInfo(
                path, Set.of("keyword"), Set.of(index), multiField);
    }
}
