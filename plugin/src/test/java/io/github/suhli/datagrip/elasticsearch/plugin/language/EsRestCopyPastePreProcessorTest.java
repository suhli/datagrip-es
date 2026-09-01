package io.github.suhli.datagrip.elasticsearch.plugin.language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EsRestCopyPastePreProcessorTest {
    @Test
    void formatsPastedJsonObjectWhenFileIsEsRest() {
        String pasted = "{\"query\":{\"term\":{\"x\":\"y\"}}}";
        String formatted = EsRestCopyPastePreProcessor.formatPastedJson(pasted);
        assertTrue(formatted.contains("\n"));
        assertTrue(formatted.contains("  \"query\""));
    }
}
