package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EsSnippetInsertHandlerTest {
    @Test
    void findsEmptyFieldKeyPairStartBeforeValueQuote() {
        String text = """
                {
                  "term": {
                    "": \"""";
        assertEquals(text.indexOf("\"\":"), EsSnippetInsertHandler.findEmptyFieldKeyPairStart(text, text.length()));
    }

    @Test
    void findsEmptyFieldKeyPairStartInsidePartialValue() {
        String text = """
                {
                  "term": {
                    "": "da""";
        int quote = text.indexOf("\"\": \"");
        assertEquals(text.indexOf("\"\":"), EsSnippetInsertHandler.findEmptyFieldKeyPairStart(text, text.length()));
        assertEquals(text.indexOf("\"\":"), EsSnippetInsertHandler.findEmptyFieldKeyPairStart(text, quote + 6));
    }
}
