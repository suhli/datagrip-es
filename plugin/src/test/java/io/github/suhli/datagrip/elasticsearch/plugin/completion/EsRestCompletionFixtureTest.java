package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchemaLoader;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestFileType;

import java.util.List;

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

    private List<String> complete(String text) {
        PsiFile file = myFixture.configureByText(EsRestFileType.INSTANCE, text);
        return EsRestCompletionContributor.lookupStringsForTest(file, myFixture.getCaretOffset());
    }
}
