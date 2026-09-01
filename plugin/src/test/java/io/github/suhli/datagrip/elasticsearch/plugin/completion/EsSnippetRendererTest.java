package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsSnippetRendererTest {
    @Test
    void rendersTermSnippetWithEmptyFieldPair() {
        EsSnippetRenderer.RenderResult result = EsSnippetRenderer.render(
                "\"term\": {\n  \"$FIELD$\": \"$VALUE$\"\n}");
        assertEquals("\"term\": {\n  \"\": \"\"\n}", result.text());
        assertTrue(result.cursorOffset() > 0);
        assertEquals('"', result.text().charAt(result.cursorOffset()));
    }

    @Test
    void rendersObjectSnippetWithEndMarker() {
        EsSnippetRenderer.RenderResult result = EsSnippetRenderer.render("\"query\": {\n  $END$\n}");
        assertEquals("\"query\": {\n  \n}", result.text());
        assertTrue(result.cursorOffset() >= 0);
    }

    @Test
    void findsEmptyFieldKeyCursorAfterFormat() {
        String formatted = """
                {
                  "term": {
                    "": ""
                  }
                }""";
        int near = formatted.indexOf("\"term\"");
        int cursor = EsSnippetRenderer.findEmptyFieldKeyCursor(formatted, near);
        assertTrue(cursor > 0);
        assertEquals('"', formatted.charAt(cursor - 1));
        assertEquals('"', formatted.charAt(cursor));
    }
}
