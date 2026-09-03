package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void deprecationStartsAtItsDeclaredClusterVersion() {
        assertEquals(EsSchemaAvailability.Status.AVAILABLE,
                EsSchemaAvailability.status("7.0.0", "8.10.0", true, "7.17.0"));
        assertEquals(EsSchemaAvailability.Status.DEPRECATED,
                EsSchemaAvailability.status("7.0.0", "8.10.0", true, "8.10.0"));
        assertEquals(EsSchemaAvailability.Status.UNAVAILABLE,
                EsSchemaAvailability.status("9.0.0", null, false, "8.17.0"));
        assertEquals(EsSchemaAvailability.Status.AVAILABLE,
                EsSchemaAvailability.status("9.0.0", "9.2.0", true, ""));
    }
}
