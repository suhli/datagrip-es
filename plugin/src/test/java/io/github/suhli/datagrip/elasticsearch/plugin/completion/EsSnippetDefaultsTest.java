package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsSnippetDefaultsTest {
    @Test
    void queryArrayIncludesBracketsAndObject() {
        String snippet = EsSnippetDefaults.bodyKeySnippet("filter", "query_array");
        assertTrue(snippet.contains("\"filter\""));
        assertTrue(snippet.contains("["));
        assertTrue(snippet.contains("{"));
        assertTrue(snippet.contains("$END$"));
    }

    @Test
    void objectIncludesBraces() {
        String snippet = EsSnippetDefaults.bodyKeySnippet("query", "object");
        assertEquals("\"query\": {\n  $END$\n}", snippet);
    }

    @Test
    void fieldObjectIncludesFieldAndValuePlaceholders() {
        String snippet = EsSnippetDefaults.bodyKeySnippet("term", "field_object");
        assertTrue(snippet.contains("\"$FIELD$\""));
        assertTrue(snippet.contains("\"$VALUE$\""));
    }

    @Test
    void fieldIncludesStringPlaceholder() {
        String snippet = EsSnippetDefaults.bodyKeySnippet("field", "field");
        assertTrue(snippet.contains("\"$FIELD$\""));
    }

    @Test
    void jsonStringValuesAreQuoted() {
        assertEquals("\"open\"", EsSnippetInsertHandler.formatJsonStringValue("open", false));
    }

    @Test
    void booleansAreNotQuoted() {
        assertEquals("true", EsSnippetInsertHandler.formatJsonStringValue("true", false));
    }
}
