package io.github.suhli.datagrip.elasticsearch.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompletionGeneratorTest {
    @TempDir
    Path tempDir;

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
        assertEquals("boolean|number", metadata.dsl().keys().get("track_total_hits").valueType());
        assertTrue(metadata.dsl().keys().get("track_total_hits").enumValues().isEmpty(),
                "UX overlay must not replace specification semantics");
    }

    @Test
    void generationIsByteIdenticalForTheSameInput() throws Exception {
        Path fixture = Path.of(Objects.requireNonNull(
                getClass().getResource("/schema-fixture.json")).toURI());
        CompletionGenerator generator = new CompletionGenerator();
        ObjectMapper json = new ObjectMapper();

        CompletionMetadata first = generator.generate(fixture, "fixed-commit");
        CompletionMetadata second = generator.generate(fixture, "fixed-commit");

        assertArrayEquals(json.writeValueAsBytes(first.api()), json.writeValueAsBytes(second.api()));
        assertArrayEquals(json.writeValueAsBytes(first.dsl()), json.writeValueAsBytes(second.dsl()));
    }

    @Test
    void genericSchemasAreRequestScopedRecursiveAndTypeAware() throws Exception {
        Path fixture = tempDir.resolve("scoped-schema.json");
        Files.writeString(fixture, """
                {
                  "endpoints": [
                    {"name":"api-a","visibility":"public","urls":[{"path":"/api-a","methods":["PUT"]}],
                     "request":{"kind":"instance_of","type":{"namespace":"api.a","name":"Request"}}},
                    {"name":"api-b","visibility":"public","urls":[{"path":"/api-b","methods":["PUT"]}],
                     "request":{"kind":"instance_of","type":{"namespace":"api.b","name":"Request"}}}
                  ],
                  "types": [
                    {"kind":"request","namespace":"api.a","name":"Request","body":[
                      {"name":"settings","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"SettingsA"}}},
                      {"name":"query","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"QueryA"}}},
                      {"name":"track_total_hits","type":{"kind":"union_of","items":[
                        {"kind":"instance_of","type":{"namespace":"_builtins","name":"boolean"}},
                        {"kind":"instance_of","type":{"namespace":"_builtins","name":"integer"}}]}},
                      {"name":"objects","type":{"kind":"array_of","value":{"kind":"instance_of","type":{"namespace":"fixture","name":"ObjectItem"}}}},
                      {"name":"labels","type":{"kind":"array_of","value":{"kind":"instance_of","type":{"namespace":"fixture","name":"Label"}}}},
                      {"name":"metadata","type":{"kind":"dictionary_of",
                        "key":{"kind":"instance_of","type":{"namespace":"_builtins","name":"string"}},
                        "value":{"kind":"instance_of","type":{"namespace":"fixture","name":"MapValue"}}}},
                      {"name":"cycle","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"Cycle"}}}
                    ]},
                    {"kind":"request","namespace":"api.b","name":"Request","body":[
                      {"name":"settings","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"SettingsB"}}},
                      {"name":"query","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"QueryB"}}}
                    ]},
                    {"kind":"interface","namespace":"fixture","name":"SettingsA","properties":[
                      {"name":"foo","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"Level1"}}}]},
                    {"kind":"interface","namespace":"fixture","name":"SettingsB","properties":[{"name":"bar"}]},
                    {"kind":"interface","namespace":"fixture","name":"QueryA","properties":[{"name":"script"}]},
                    {"kind":"interface","namespace":"fixture","name":"QueryB","properties":[{"name":"source"}]},
                    {"kind":"interface","namespace":"fixture","name":"Level1","properties":[
                      {"name":"child","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"Level2"}}}]},
                    {"kind":"interface","namespace":"fixture","name":"Level2","properties":[
                      {"name":"grandchild","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"Level3"}}}]},
                    {"kind":"interface","namespace":"fixture","name":"Level3","properties":[{"name":"leaf"}]},
                    {"kind":"interface","namespace":"fixture","name":"ObjectItem","properties":[{"name":"object_leaf"}]},
                    {"kind":"enum","namespace":"fixture","name":"Label","members":[{"name":"red"},{"name":"blue"}]},
                    {"kind":"interface","namespace":"fixture","name":"MapValue","properties":[{"name":"map_leaf"}]},
                    {"kind":"interface","namespace":"fixture","name":"Cycle","properties":[
                      {"name":"next","type":{"kind":"instance_of","type":{"namespace":"fixture","name":"Cycle"}}}]}
                  ]
                }
                """);

        CompletionMetadata metadata = new CompletionGenerator().generate(fixture, "fixture");
        var a = metadata.dsl().requestBodySchemas().get("api.a.Request");
        var b = metadata.dsl().requestBodySchemas().get("api.b.Request");

        assertEquals(List.of("foo"), a.get("settings").node().children());
        assertEquals(List.of("bar"), b.get("settings").node().children());
        assertEquals("boolean|number", a.get("track_total_hits").node().valueType());
        assertEquals("array<object>", a.get("objects").node().valueType());
        assertTrue(a.get("labels").node().valueType().startsWith("array<"));
        assertEquals(List.of("red", "blue"), a.get("labels").node().enumValues());

        var types = metadata.dsl().typeSchemas();
        assertTrue(types.get("fixture.SettingsA").containsKey("foo"));
        assertFalse(types.get("fixture.SettingsA").containsKey("bar"));
        assertTrue(types.get("fixture.SettingsB").containsKey("bar"));
        assertTrue(types.get("fixture.QueryA").containsKey("script"));
        assertTrue(types.get("fixture.QueryB").containsKey("source"));
        assertTrue(types.get("fixture.Level3").containsKey("leaf"));
        assertTrue(types.get("fixture.ObjectItem").containsKey("object_leaf"));
        assertTrue(types.get("fixture.MapValue").containsKey("map_leaf"));
        assertEquals(List.of("fixture.MapValue"), a.get("metadata").dictionaryValueTypes());
        assertTrue(types.containsKey("fixture.Cycle"));
        assertTrue(types.size() < 20, "cycle protection must keep the type graph bounded");
    }
}
