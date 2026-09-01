package io.github.suhli.datagrip.elasticsearch.codegen;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionGeneratorTest {
    @Test
    void generatesApiAndDslFromFixture() throws Exception {
        Path fixture = Path.of(Objects.requireNonNull(
                        getClass().getResource("/schema-fixture.json")).toURI());
        CompletionMetadata metadata = new CompletionGenerator().generate(fixture, "fixture");

        assertTrue(metadata.api().endpoints().stream().anyMatch(e -> e.name().equals("search")));
        assertTrue(metadata.api().endpoints().stream()
                .filter(e -> e.name().equals("search"))
                .flatMap(e -> e.queryParams().stream())
                .anyMatch(p -> p.name().equals("expand_wildcards")
                        && p.enumValues().contains("open")));
        assertTrue(metadata.api().endpoints().stream()
                .filter(e -> e.name().equals("cluster.health"))
                .flatMap(e -> e.queryParams().stream())
                .noneMatch(p -> p.name().equals("expand_wildcards")));

        assertTrue(metadata.dsl().keys().containsKey("bool"));
        assertTrue(metadata.dsl().keys().containsKey("term"));
        assertTrue(metadata.dsl().keys().get("bool").children().contains("filter"));
        assertTrue(metadata.dsl().keys().containsKey("terms"));
        assertTrue(metadata.dsl().keys().get("avg").category().contains("aggregation"));
        assertFalse(metadata.dsl().endpointBodyRoots().get("search").isEmpty());
        assertTrue(metadata.dsl().keys().get("match.operator").enumValues().contains("and"));
    }
}
