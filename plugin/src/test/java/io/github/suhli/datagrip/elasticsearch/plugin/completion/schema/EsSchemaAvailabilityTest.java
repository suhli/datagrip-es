package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsSchemaAvailabilityTest {
    @Test
    void filtersByMinimumVersionAndAllowsUnknownClusterVersion() {
        EsSchemaModels.DslNode node = new EsSchemaModels.DslNode(
                "new_query", "query_dsl", List.of("query"), List.of(), "object",
                false, List.of(), null, "", "9.1.0", null, false, 100);
        assertFalse(EsSchemaAvailability.isAvailable(node, "8.17.3"));
        assertFalse(EsSchemaAvailability.isAvailable(node, "9.0.9"));
        assertTrue(EsSchemaAvailability.isAvailable(node, "9.1.0"));
        assertTrue(EsSchemaAvailability.isAvailable(node, "9.2.0-SNAPSHOT"));
        assertTrue(EsSchemaAvailability.isAvailable(node, ""));
    }
}
