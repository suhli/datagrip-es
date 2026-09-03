package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataService;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchemaLoader;
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
