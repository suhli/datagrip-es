package io.github.suhli.datagrip.elasticsearch.codegen;

import java.util.List;
import java.util.Map;

record CompletionMetadata(ApiCompletionDocument api, DslCompletionDocument dsl) {}

record ApiCompletionDocument(
        String specVersion,
        String generatedAt,
        List<EndpointCompletion> endpoints) {}

record EndpointCompletion(
        String name,
        List<String> methods,
        List<String> paths,
        List<PathParameter> pathParams,
        List<QueryParameter> queryParams,
        String requestBodyType,
        String documentation,
        String minVersion,
        String deprecatedVersion,
        boolean deprecated) {}

record PathParameter(String name, String description) {}

record QueryParameter(
        String name,
        String type,
        List<String> enumValues,
        String description,
        String minVersion,
        String deprecatedVersion,
        boolean deprecated) {}

record DslCompletionDocument(
        String specVersion,
        String generatedAt,
        Map<String, DslKey> keys,
        Map<String, List<String>> endpointBodyRoots,
        Map<String, Map<String, GenericProperty>> requestBodySchemas,
        Map<String, Map<String, GenericProperty>> typeSchemas) {}

record GenericProperty(
        DslKey node,
        List<String> childTypes,
        List<String> dictionaryValueTypes) {}

record DslKey(
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
