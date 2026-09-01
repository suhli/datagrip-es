package io.github.suhli.datagrip.elasticsearch.plugin.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsRestDocumentFormatterTest {
    @Test
    void formatsRequestLineAndJsonBody() {
        String input = "GET /game_logs/_search\n{\"query\":{\"match_all\":{}}}";
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.startsWith("GET /game_logs/_search\n"));
        assertTrue(formatted.contains("  \"query\""));
        assertTrue(formatted.contains("    \"match_all\""));
    }

    @Test
    void normalizesMethodCaseAndSpacing() {
        String formatted = EsRestDocumentFormatter.format("post  /index/_doc");
        assertEquals("POST /index/_doc", formatted);
    }

    @Test
    void preservesInlineCommentOnRequestLine() {
        String formatted = EsRestDocumentFormatter.format("GET /_cluster/health # quick check");
        assertEquals("GET /_cluster/health # quick check", formatted);
    }

    @Test
    void formatsMultipleRequests() {
        String input = """
                GET /a/_search
                {"size":1}

                POST /b/_doc
                {"x":1}
                """;
        String formatted = EsRestDocumentFormatter.format(input);
        assertTrue(formatted.contains("GET /a/_search\n"));
        assertTrue(formatted.contains("POST /b/_doc\n"));
        assertTrue(formatted.contains("  \"size\""));
        assertTrue(formatted.contains("  \"x\""));
    }
}
