package io.github.suhli.datagrip.elasticsearch.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds compact completion metadata from elasticsearch-specification schema.json.
 * Supports both nested {@code name:{namespace,name}} schema shapes and simpler fixtures.
 */
public final class CompletionGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SEARCH_LIKE = Set.of(
            "search", "async_search.submit", "msearch", "scroll", "search_template");

    public CompletionMetadata generate(Path schemaPath, String specVersion) throws Exception {
        JsonNode root = JSON.readTree(schemaPath.toFile());
        Map<String, JsonNode> types = indexTypes(root.path("types"));
        List<EndpointCompletion> endpoints = extractEndpoints(root.path("endpoints"), types);
        Map<String, DslKey> keys = new TreeMap<>();
        mergeOverlay(keys);
        extractQueryDsl(types, keys);
        extractAggregations(types, keys);
        extractSearchBody(types, keys);
        Map<String, List<String>> endpointBodyRoots = extractRequestBodies(endpoints, types, keys);
        addSearchBodyRoots(endpointBodyRoots, endpoints, keys);
        String generatedAt = "";
        return new CompletionMetadata(
                new ApiCompletionDocument(specVersion, generatedAt, endpoints),
                new DslCompletionDocument(specVersion, generatedAt, keys, endpointBodyRoots));
    }

    private static Map<String, JsonNode> indexTypes(JsonNode types) {
        Map<String, JsonNode> indexed = new LinkedHashMap<>();
        if (!types.isArray()) return indexed;
        for (JsonNode type : types) {
            TypeName tn = typeName(type);
            if (tn != null) indexed.put(tn.key(), type);
        }
        return indexed;
    }

    private static List<EndpointCompletion> extractEndpoints(JsonNode endpoints, Map<String, JsonNode> types) {
        List<EndpointCompletion> result = new ArrayList<>();
        if (!endpoints.isArray()) return result;
        for (JsonNode endpoint : endpoints) {
            if (isPrivate(endpoint)) continue;
            String name = text(endpoint, "name");
            if (name.isEmpty()) continue;

            LinkedHashSet<String> methods = new LinkedHashSet<>();
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            boolean deprecated = false;
            String deprecatedVersion = null;
            for (JsonNode url : endpoint.path("urls")) {
                String path = text(url, "path");
                if (!path.isEmpty()) paths.add(path);
                for (JsonNode method : url.path("methods")) {
                    if (method.isTextual()) methods.add(method.asText().toUpperCase(Locale.ROOT));
                }
                JsonNode deprecation = url.path("deprecation");
                if (!deprecation.isMissingNode() && !deprecation.isNull()) {
                    deprecated = true;
                    if (deprecatedVersion == null) {
                        deprecatedVersion = firstNonBlank(
                                text(deprecation, "version"),
                                text(deprecation, "asOf"));
                    }
                }
            }
            if (methods.isEmpty() || paths.isEmpty()) continue;

            TypeName requestTypeName = typeName(endpoint.path("request"));
            String requestType = requestTypeName == null ? "" : requestTypeName.key();
            List<QueryParameter> queryParams = extractQueryParams(types.get(requestType), types);
            List<PathParameter> pathParams = extractPathParams(paths);

            result.add(new EndpointCompletion(
                    name,
                    List.copyOf(methods),
                    List.copyOf(paths),
                    pathParams,
                    queryParams,
                    requestType.isBlank() ? null : requestType,
                    firstNonBlank(text(endpoint, "docUrl"), text(endpoint, "description")),
                    firstNonBlank(since(endpoint), null),
                    deprecatedVersion,
                    deprecated));
        }
        result.sort(Comparator.comparing(EndpointCompletion::name));
        return List.copyOf(result);
    }

    private static boolean isPrivate(JsonNode endpoint) {
        String visibility = text(endpoint, "visibility");
        if ("private".equalsIgnoreCase(visibility)) return true;
        JsonNode stack = endpoint.path("availability").path("stack");
        if (stack.isObject() && "private".equalsIgnoreCase(text(stack, "visibility"))) return true;
        return false;
    }

    private static String since(JsonNode endpoint) {
        JsonNode stack = endpoint.path("availability").path("stack");
        return text(stack, "since");
    }

    private static List<PathParameter> extractPathParams(Set<String> paths) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String path : paths) {
            int i = 0;
            while (i < path.length()) {
                int start = path.indexOf('{', i);
                if (start < 0) break;
                int end = path.indexOf('}', start + 1);
                if (end < 0) break;
                names.add(path.substring(start + 1, end));
                i = end + 1;
            }
        }
        List<PathParameter> params = new ArrayList<>();
        for (String name : names) params.add(new PathParameter(name, null));
        return List.copyOf(params);
    }

    private static List<QueryParameter> extractQueryParams(JsonNode requestType, Map<String, JsonNode> types) {
        if (requestType == null) return List.of();
        LinkedHashMap<String, QueryParameter> params = new LinkedHashMap<>();
        collectQueryParams(requestType, types, params, 0);
        return List.copyOf(params.values());
    }

    private static void collectQueryParams(
            JsonNode type,
            Map<String, JsonNode> types,
            Map<String, QueryParameter> output,
            int depth) {
        if (type == null || depth > 6) return;
        JsonNode inherits = type.get("inherits");
        if (inherits != null && inherits.isObject()) {
            TypeName parent = typeName(inherits.path("type").isMissingNode() ? inherits : inherits.path("type"));
            if (parent != null) collectQueryParams(types.get(parent.key()), types, output, depth + 1);
        } else if (inherits != null && inherits.isArray()) {
            for (JsonNode inherit : inherits) {
                TypeName parent = typeName(inherit.path("type").isMissingNode() ? inherit : inherit.path("type"));
                if (parent != null) collectQueryParams(types.get(parent.key()), types, output, depth + 1);
            }
        }
        for (JsonNode behavior : type.path("attachedBehaviors")) {
            if (behavior.isTextual()) {
                collectQueryParams(types.get("_types." + behavior.asText()), types, output, depth + 1);
                collectQueryParams(types.get(behavior.asText()), types, output, depth + 1);
            }
        }
        JsonNode query = type.get("query");
        List<JsonNode> props = propertyList(query);
        if (props.isEmpty() && "interface".equals(text(type, "kind"))) {
            props = propertyList(type.get("properties"));
        }
        for (JsonNode prop : props) {
            String name = text(prop, "name");
            if (name.isEmpty() || output.containsKey(name)) continue;
            JsonNode typeNode = prop.path("type");
            String typeName = typeRefName(typeNode);
            List<String> enums = resolveEnumValues(typeNode, types);
            boolean deprecated = !prop.path("deprecation").isMissingNode()
                    && !prop.path("deprecation").isNull();
            String deprecatedVersion = deprecated ? firstNonBlank(
                    text(prop.path("deprecation"), "version"),
                    text(prop.path("deprecation"), "asOf")) : null;
            output.put(name, new QueryParameter(
                    name,
                    typeName.isEmpty() ? "string" : typeName,
                    enums,
                    firstNonBlank(text(prop, "description"), null),
                    firstNonBlank(text(prop.path("availability").path("stack"), "since"), null),
                    deprecatedVersion,
                    deprecated));
        }
    }

    private static List<String> resolveEnumValues(JsonNode typeNode, Map<String, JsonNode> types) {
        return resolveEnumValues(typeNode, types, 0);
    }

    private static List<String> resolveEnumValues(JsonNode typeNode, Map<String, JsonNode> types, int depth) {
        if (typeNode == null || typeNode.isMissingNode() || depth > 6) return List.of();
        String kind = text(typeNode, "kind");
        if ("instance_of".equals(kind)) {
            TypeName tn = typeName(typeNode.path("type"));
            if (tn == null) return List.of();
            JsonNode resolved = types.get(tn.key());
            if (resolved == null) return List.of();
            if ("enum".equals(text(resolved, "kind"))) {
                List<String> values = new ArrayList<>();
                for (JsonNode member : resolved.path("members")) {
                    String value = firstNonBlank(text(member, "name"), text(member, "value"));
                    if (value != null && !value.isBlank()) values.add(value);
                }
                return List.copyOf(values);
            }
            if ("type_alias".equals(text(resolved, "kind"))) {
                return resolveEnumValues(resolved.get("type"), types, depth + 1);
            }
        }
        if ("union_of".equals(kind)) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode item : typeNode.path("items")) {
                values.addAll(resolveEnumValues(item, types, depth + 1));
            }
            return List.copyOf(values);
        }
        if ("array_of".equals(kind)) {
            return resolveEnumValues(typeNode.path("value"), types, depth + 1);
        }
        return List.of();
    }

    private static void extractQueryDsl(Map<String, JsonNode> types, Map<String, DslKey> keys) {
        JsonNode container = types.get("_types.query_dsl.QueryContainer");
        if (container == null) return;
        for (Variant variant : variantsFromProperties(container)) {
            String key = variant.name();
            JsonNode type = types.get(variant.typeKey());
            List<String> children = propertyNames(type);
            boolean fieldRef = looksLikeFieldQuery(key, variant.typeNode());
            boolean deprecated = variant.deprecated();
            keys.put(key, new DslKey(
                    key,
                    "query_dsl",
                    List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not",
                            "constant_score.filter", "nested.query", "function_score.query"),
                    children,
                    fieldRef ? "field_object" : "object",
                    fieldRef,
                    List.of(),
                    defaultQuerySnippet(key, fieldRef),
                    "Query DSL",
                    variant.since(),
                    variant.deprecatedVersion(),
                    deprecated,
                    deprecated ? 70 : 100));
            addChildKeys(keys, key, type, types, "query_dsl");
        }
    }

    private static void extractAggregations(Map<String, JsonNode> types, Map<String, DslKey> keys) {
        JsonNode container = types.get("_types.aggregations.AggregationContainer");
        if (container == null) return;
        for (Variant variant : variantsFromProperties(container)) {
            String key = variant.name();
            if ("aggs".equals(key) || "aggregations".equals(key) || "meta".equals(key)) continue;
            JsonNode type = types.get(variant.typeKey());
            List<String> children = propertyNames(type);
            String category = categorizeAggregation(key);
            keys.put(key, new DslKey(
                    key,
                    category,
                    List.of("aggregation", "aggs.*", "aggregations.*"),
                    children,
                    "object",
                    children.contains("field"),
                    List.of(),
                    defaultAggSnippet(key, children),
                    categoryLabel(category),
                    variant.since(),
                    variant.deprecatedVersion(),
                    variant.deprecated(),
                    variant.deprecated() ? 60 : 90));
            addChildKeys(keys, key, type, types, category);
        }
        keys.putIfAbsent("aggs", new DslKey(
                "aggs", "aggregation_container", List.of("search", "aggregation"),
                List.of(), "object", false, List.of(),
                "\"aggs\": {\n  \"$NAME$\": {\n    $END$\n  }\n}",
                "Aggregations", null, null, false, 95));
        keys.putIfAbsent("aggregations", new DslKey(
                "aggregations", "aggregation_container", List.of("search", "aggregation"),
                List.of(), "object", false, List.of(),
                "\"aggregations\": {\n  \"$NAME$\": {\n    $END$\n  }\n}",
                "Aggregations", null, null, false, 94));
    }

    private static void extractSearchBody(Map<String, JsonNode> types, Map<String, DslKey> keys) {
        JsonNode request = types.get("_global.search.Request");
        if (request == null) request = types.get("search.Request");
        if (request == null) return;
        for (JsonNode prop : propertyList(request.get("body"))) {
            String name = text(prop, "name");
            if (name.isEmpty()) continue;
            List<String> enums = resolveEnumValues(prop.path("type"), types);
            String valueType = enums.isEmpty() ? inferValueType(prop.path("type")) : "enum";
            boolean fieldRef = "fields".equals(name) || "_source".equals(name) || "sort".equals(name);
            boolean deprecated = !prop.path("deprecation").isMissingNode() && !prop.path("deprecation").isNull();
            keys.putIfAbsent(name, new DslKey(
                    name,
                    "search_body",
                    List.of("search"),
                    List.of(),
                    valueType,
                    fieldRef,
                    enums,
                    "\"" + name + "\": $END$",
                    "Search body",
                    null,
                    deprecated ? text(prop.path("deprecation"), "version") : null,
                    deprecated,
                    110));
            for (JsonNode alias : prop.path("aliases")) {
                if (alias.isTextual()) {
                    keys.putIfAbsent(alias.asText(), new DslKey(
                            alias.asText(), "search_body", List.of("search"), List.of(),
                            valueType, fieldRef, enums, "\"" + alias.asText() + "\": $END$",
                            "Search body", null, null, false, 109));
                }
            }
        }
    }

    private static void addChildKeys(
            Map<String, DslKey> keys,
            String parent,
            JsonNode type,
            Map<String, JsonNode> types,
            String category) {
        if (type == null) return;
        for (JsonNode prop : propertyList(type.get("properties"))) {
            String name = text(prop, "name");
            if (name.isEmpty()) continue;
            String compound = parent + "." + name;
            List<String> enums = resolveEnumValues(prop.path("type"), types);
            boolean fieldRef = "field".equals(name) || "fields".equals(name)
                    || isFieldDictionary(prop.path("type"));
            String valueType = fieldRef ? "field"
                    : (!enums.isEmpty() ? "enum" : inferValueType(prop.path("type")));
            keys.putIfAbsent(compound, new DslKey(
                    name,
                    category + "_property",
                    List.of(parent),
                    List.of(),
                    valueType,
                    fieldRef,
                    enums,
                    null,
                    parent + " property",
                    null,
                    null,
                    false,
                    80));
        }
    }

    private static boolean isFieldDictionary(JsonNode typeNode) {
        return "dictionary_of".equals(text(typeNode, "kind"))
                && "Field".equals(typeRefName(typeNode.path("key")));
    }

    private static Map<String, List<String>> extractRequestBodies(
            List<EndpointCompletion> endpoints,
            Map<String, JsonNode> types,
            Map<String, DslKey> keys) {
        Map<String, List<String>> roots = new LinkedHashMap<>();
        for (EndpointCompletion endpoint : endpoints) {
            JsonNode request = types.get(endpoint.requestBodyType());
            if (request == null) continue;
            List<String> endpointRoots = new ArrayList<>();
            for (JsonNode property : propertyList(request.get("body"))) {
                String name = text(property, "name");
                if (name.isBlank()) continue;
                endpointRoots.add(name);
                List<String> enums = resolveEnumValues(property.path("type"), types);
                TypeName referenced = referencedType(property.path("type"));
                JsonNode childType = referenced == null ? null : types.get(referenced.key());
                List<String> children = propertyNames(childType);
                String valueType = enums.isEmpty() ? inferValueType(property.path("type")) : "enum";
                boolean deprecated = !property.path("deprecation").isMissingNode()
                        && !property.path("deprecation").isNull();
                keys.putIfAbsent(name, new DslKey(
                        name,
                        "request_body",
                        List.of(endpoint.name()),
                        children,
                        valueType,
                        "field".equals(name) || "fields".equals(name),
                        enums,
                        null,
                        endpoint.name() + " request body",
                        firstNonBlank(text(property.path("availability").path("stack"), "since"), null),
                        deprecated ? firstNonBlank(
                                text(property.path("deprecation"), "version"),
                                text(property.path("deprecation"), "asOf")) : null,
                        deprecated,
                        100));
                addChildKeys(keys, name, childType, types, "request_body");
            }
            if (!endpointRoots.isEmpty()) roots.put(endpoint.name(), List.copyOf(endpointRoots));
        }
        return roots;
    }

    private static void addSearchBodyRoots(
            Map<String, List<String>> roots,
            List<EndpointCompletion> endpoints,
            Map<String, DslKey> keys) {
        List<String> searchKeys = keys.values().stream()
                .filter(k -> "search_body".equals(k.category()))
                .map(DslKey::key)
                .sorted()
                .toList();
        for (EndpointCompletion endpoint : endpoints) {
            if (SEARCH_LIKE.contains(endpoint.name())
                    || endpoint.paths().stream().anyMatch(p -> p.contains("/_search"))) {
                roots.put(endpoint.name(), searchKeys);
            }
        }
        roots.putIfAbsent("_search", searchKeys);
        roots.putIfAbsent("search", searchKeys);
    }

    private static void mergeOverlay(Map<String, DslKey> keys) {
        putOverlay(keys, "bool", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of("filter", "must", "must_not", "should", "minimum_should_match", "boost"),
                "object", false, List.of(),
                "\"bool\": {\n  $END$\n}", 120);
        putOverlay(keys, "term", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of(), "field_object", true, List.of(),
                "\"term\": {\n  \"$FIELD$\": \"$VALUE$\"\n}", 120);
        putOverlay(keys, "terms", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of(), "field_object", true, List.of(),
                "\"terms\": {\n  \"$FIELD$\": [\"$VALUE$\"]\n}", 118);
        putOverlay(keys, "range", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of(), "field_object", true, List.of(),
                "\"range\": {\n  \"$FIELD$\": {\n    \"gte\": \"$VALUE$\",\n    \"lte\": \"$VALUE$\"\n  }\n}",
                118);
        putOverlay(keys, "match", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of(), "field_object", true, List.of(),
                "\"match\": {\n  \"$FIELD$\": \"$VALUE$\"\n}", 118);
        putOverlay(keys, "exists", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of("field"), "object", true, List.of(),
                "\"exists\": {\n  \"field\": \"$FIELD$\"\n}", 115);
        putOverlay(keys, "match_all", "query_dsl",
                List.of("query", "bool.must", "bool.filter", "bool.should", "bool.must_not"),
                List.of(), "object", false, List.of(),
                "\"match_all\": {}", 110);

        for (String child : List.of("filter", "must", "must_not", "should", "minimum_should_match", "boost")) {
            String valueType = switch (child) {
                case "filter", "must", "must_not", "should" -> "query_array";
                case "boost" -> "number";
                default -> "string";
            };
            keys.put("bool." + child, new DslKey(
                    child, "query_dsl_property", List.of("bool"), List.of(), valueType,
                    false, List.of(), null, "bool property", null, null, false, 100));
        }

        putOverlay(keys, "terms", "bucket_aggregation",
                List.of("aggregation", "aggs.*", "aggregations.*"),
                List.of("field", "size", "order", "min_doc_count"),
                "object", true, List.of(),
                "\"terms\": {\n  \"field\": \"$FIELD$\"\n}", 120);
        putOverlay(keys, "date_histogram", "bucket_aggregation",
                List.of("aggregation", "aggs.*", "aggregations.*"),
                List.of("field", "calendar_interval", "fixed_interval"),
                "object", true, List.of(),
                "\"date_histogram\": {\n  \"field\": \"$FIELD$\",\n  \"calendar_interval\": \"$INTERVAL$\"\n}",
                115);
        for (String metric : List.of("avg", "sum", "min", "max", "cardinality", "value_count", "stats")) {
            putOverlay(keys, metric, "metric_aggregation",
                    List.of("aggregation", "aggs.*", "aggregations.*"),
                    List.of("field"), "object", true, List.of(),
                    "\"" + metric + "\": {\n  \"field\": \"$FIELD$\"\n}", 110);
        }

        keys.put("match.operator", new DslKey(
                "operator", "query_dsl_property", List.of("match"), List.of(), "enum",
                false, List.of("and", "or"), null, "match operator", null, null, false, 100));
        keys.put("track_total_hits", new DslKey(
                "track_total_hits", "search_body", List.of("search"), List.of(), "boolean",
                false, List.of("true", "false"), "\"track_total_hits\": $VALUE$",
                "Search body", null, null, false, 105));
    }

    private static void putOverlay(
            Map<String, DslKey> keys,
            String key,
            String category,
            List<String> parents,
            List<String> children,
            String valueType,
            boolean fieldReference,
            List<String> enums,
            String snippet,
            int priority) {
        keys.put(key, new DslKey(
                key, category, parents, children, valueType, fieldReference, enums, snippet,
                categoryLabel(category), null, null, false, priority));
    }

    private static List<Variant> variantsFromProperties(JsonNode container) {
        List<Variant> result = new ArrayList<>();
        JsonNode variants = container.get("variants");
        if (variants != null && variants.isArray()) {
            for (JsonNode variant : variants) {
                String name = firstNonBlank(text(variant, "name"), typeRefName(variant.path("type")));
                TypeName tn = typeName(variant.path("type"));
                if (name != null && tn != null) {
                    result.add(new Variant(name, tn.key(), variant.path("type"), false, null, null));
                }
            }
            return result;
        }
        for (JsonNode prop : propertyList(container.get("properties"))) {
            String name = text(prop, "name");
            TypeName tn = referencedType(prop.path("type"));
            if (name.isEmpty() || tn == null) continue;
            boolean deprecated = !prop.path("deprecation").isMissingNode() && !prop.path("deprecation").isNull();
            String deprecatedVersion = deprecated ? text(prop.path("deprecation"), "version") : null;
            String since = text(prop.path("availability").path("stack"), "since");
            result.add(new Variant(name, tn.key(), prop.path("type"), deprecated, since, deprecatedVersion));
        }
        return result;
    }

    private static TypeName referencedType(JsonNode typeNode) {
        if (typeNode == null || typeNode.isMissingNode()) return null;
        String kind = text(typeNode, "kind");
        if ("instance_of".equals(kind)) return typeName(typeNode.path("type"));
        if ("dictionary_of".equals(kind)) return typeName(typeNode.path("value").path("type"));
        if ("union_of".equals(kind)) {
            for (JsonNode item : typeNode.path("items")) {
                TypeName tn = referencedType(item);
                if (tn != null) return tn;
            }
        }
        return typeName(typeNode);
    }

    private static List<JsonNode> propertyList(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return result;
        if (node.isArray()) {
            node.forEach(result::add);
            return result;
        }
        if (node.isObject() && node.path("properties").isArray()) {
            node.path("properties").forEach(result::add);
        }
        return result;
    }

    private static List<String> propertyNames(JsonNode type) {
        if (type == null) return List.of();
        List<String> names = new ArrayList<>();
        for (JsonNode prop : propertyList(type.get("properties"))) {
            String name = text(prop, "name");
            if (!name.isEmpty()) names.add(name);
        }
        return List.copyOf(names);
    }

    private static boolean looksLikeFieldQuery(String key, JsonNode typeNode) {
        if (Set.of("term", "terms", "match", "match_phrase", "range", "prefix", "wildcard",
                "regexp", "fuzzy").contains(key)) return true;
        // A regular object having a "field" or "fields" property (for example
        // multi_match) is not a dictionary whose keys are mapping field names.
        return isFieldDictionary(typeNode);
    }

    private static String defaultQuerySnippet(String key, boolean fieldRef) {
        if ("exists".equals(key)) return "\"exists\": {\n  \"field\": \"$FIELD$\"\n}";
        if (fieldRef) return "\"" + key + "\": {\n  \"$FIELD$\": \"$VALUE$\"\n}";
        return "\"" + key + "\": {\n  $END$\n}";
    }

    private static String defaultAggSnippet(String key, List<String> children) {
        if (children.contains("field")) return "\"" + key + "\": {\n  \"field\": \"$FIELD$\"\n}";
        return "\"" + key + "\": {\n  $END$\n}";
    }

    private static String categorizeAggregation(String key) {
        if (Set.of("avg", "sum", "min", "max", "stats", "extended_stats", "cardinality",
                "value_count", "percentiles", "percentile_ranks", "top_hits", "median_absolute_deviation",
                "string_stats", "rate", "boxplot", "matrix_stats", "geo_bounds", "geo_centroid",
                "scripted_metric", "weighted_avg").contains(key)) {
            return "metric_aggregation";
        }
        if (Set.of("bucket_sort", "bucket_selector", "bucket_script", "derivative", "moving_fn",
                "moving_avg", "cumsum", "cumulative_cardinality", "serial_diff", "normalize",
                "avg_bucket", "sum_bucket", "min_bucket", "max_bucket",
                "stats_bucket", "extended_stats_bucket", "percentiles_bucket").contains(key)) {
            return "pipeline_aggregation";
        }
        return "bucket_aggregation";
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "query_dsl" -> "Query DSL";
            case "bucket_aggregation" -> "Bucket aggregation";
            case "metric_aggregation" -> "Metric aggregation";
            case "pipeline_aggregation" -> "Pipeline aggregation";
            case "aggregation_container" -> "Aggregation";
            case "search_body" -> "Search body";
            default -> category;
        };
    }

    private static String inferValueType(JsonNode typeNode) {
        String name = typeRefName(typeNode);
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "boolean", "booleanvalue" -> "boolean";
            case "integer", "long", "double", "float", "number", "uint" -> "number";
            case "field", "fields" -> "field";
            default -> "string";
        };
    }

    private static TypeName typeName(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.has("namespace") || (node.has("name") && node.path("name").isTextual()
                && !node.path("name").isObject())) {
            String ns = text(node, "namespace");
            String name = text(node, "name");
            if (!name.isEmpty()) return new TypeName(ns, name);
        }
        JsonNode nested = node.get("name");
        if (nested != null && nested.isObject()) {
            String ns = text(nested, "namespace");
            String name = text(nested, "name");
            if (!name.isEmpty()) return new TypeName(ns, name);
        }
        if (node.has("type") && node.path("type").isObject()) {
            return typeName(node.path("type"));
        }
        return null;
    }

    private static String typeRefName(JsonNode typeNode) {
        if (typeNode == null || typeNode.isMissingNode()) return "";
        if ("instance_of".equals(text(typeNode, "kind"))) {
            TypeName tn = typeName(typeNode.path("type"));
            return tn == null ? "" : tn.name();
        }
        TypeName tn = typeName(typeNode);
        return tn == null ? text(typeNode, "name") : tn.name();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isObject() && value.has("name") && value.path("name").isTextual()
                && "name".equals(field)) {
            return value.path("name").asText();
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private record TypeName(String namespace, String name) {
        String key() {
            return (namespace == null || namespace.isBlank()) ? name : namespace + "." + name;
        }
    }

    private record Variant(
            String name,
            String typeKey,
            JsonNode typeNode,
            boolean deprecated,
            String since,
            String deprecatedVersion) {}
}
