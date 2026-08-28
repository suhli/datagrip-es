package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class MappingFlattener {
    private MappingFlattener() {}

    public static List<Field> flatten(JsonNode mappingRoot) {
        List<Field> fields = new ArrayList<>();
        JsonNode mappings = mappingRoot.path("mappings");
        if (mappings.isMissingNode()) mappings = mappingRoot;
        walk("", mappings.path("properties"), fields);
        return List.copyOf(fields);
    }

    private static void walk(String prefix, JsonNode properties, List<Field> output) {
        if (!properties.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> iterator = properties.fields();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode definition = entry.getValue();
            String type = definition.path("type").asText(definition.has("properties") ? "object" : "object");
            output.add(new Field(name, type, EsTypes.jdbcType(type), false));
            walk(name, definition.path("properties"), output);
            JsonNode multifields = definition.path("fields");
            if (multifields.isObject()) {
                multifields.fields().forEachRemaining(sub -> {
                    String subType = sub.getValue().path("type").asText("keyword");
                    output.add(new Field(name + "." + sub.getKey(), subType, EsTypes.jdbcType(subType), true));
                });
            }
        }
    }

    public record Field(String name, String esType, int jdbcType, boolean multiField) {}
}
