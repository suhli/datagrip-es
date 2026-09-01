package io.github.suhli.datagrip.elasticsearch.plugin.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsRestDocumentFormatterTest {
    @Test
    void formatsOnlyJsonBody() {
        String input = "GET /game_logs/_search\n{\"query\":{\"match_all\":{}}}";
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.startsWith("GET /game_logs/_search\n"));
        assertTrue(formatted.contains("  \"query\""));
        assertTrue(formatted.contains("    \"match_all\""));
    }

    @Test
    void preservesRequestLineExactly() {
        String input = "post  /index/_doc";
        assertEquals(input, EsRestDocumentFormatter.format(input));
    }

    @Test
    void preservesIndentedRequestLine() {
        String input = "    GET /index/_search\n{\"x\":1}";
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.startsWith("    GET /index/_search\n"));
        assertTrue(formatted.contains("  \"x\""));
    }

    @Test
    void preservesCommentsAndSeparators() {
        String input = """
                GET /_cat/indices?v

                //
                GET /a/_search
                {"size":1}
                """;
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.contains("GET /_cat/indices?v"));
        assertTrue(formatted.contains("//\n"));
        assertTrue(formatted.contains("GET /a/_search\n"));
        assertTrue(formatted.contains("  \"size\""));
    }

    @Test
    void doesNotLeaveDanglingBraceAfterNestedJson() {
        String input = """
                GET /a/_search
                {"size":10,"query":{"bool":{"filter":[{"term":{"ev.keyword":""}}]}}}
                """;
        String formatted = EsRestDocumentFormatter.format(input);
        assertFalse(formatted.endsWith("}\n}"));
        assertEquals(formatted.trim().charAt(formatted.trim().length() - 1), '}');
        assertTrue(formatted.contains("\"ev.keyword\""));
    }

    @Test
    void formatsJsonSelectionWithoutRequestLine() {
        String input = "{\"query\":{\"match_all\":{}}}";
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.startsWith("{\n"));
        assertTrue(formatted.contains("  \"query\""));
    }
}
