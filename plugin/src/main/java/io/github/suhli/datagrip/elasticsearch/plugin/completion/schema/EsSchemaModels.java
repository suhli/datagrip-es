package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import java.util.List;

public final class EsSchemaModels {
    private EsSchemaModels() {}

    public record Endpoint(
            String name,
            List<String> methods,
            List<String> paths,
            List<QueryParam> queryParams,
            String requestBodyType,
            String documentation,
            String minVersion,
            String deprecatedVersion,
            boolean deprecated) {}

    public record QueryParam(
            String name,
            String type,
            List<String> enumValues,
            String description,
            String minVersion,
            String deprecatedVersion,
            boolean deprecated) {}

    public record DslNode(
            String key,
            String category,
            List<String> parents,
            List<String> children,
            String valueType,
            boolean fieldReference,
            List<String> enumValues,
            String snippet,
            String description,
            String minVersion,
            String deprecatedVersion,
            boolean deprecated,
            int priority) {}

    public record GenericProperty(
            DslNode node,
            List<String> childTypes,
            List<String> dictionaryValueTypes) {}
}
