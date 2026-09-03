package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable loaded completion schema. */
public final class EsCompletionSchema {
    private final List<EsSchemaModels.Endpoint> endpoints;
    private final Map<String, EsSchemaModels.DslNode> keys;
    private final Map<String, List<String>> endpointBodyRoots;
    private final Map<String, Map<String, EsSchemaModels.GenericProperty>> requestBodySchemas;
    private final Map<String, Map<String, EsSchemaModels.GenericProperty>> typeSchemas;

    public EsCompletionSchema(
            List<EsSchemaModels.Endpoint> endpoints,
            Map<String, EsSchemaModels.DslNode> keys,
            Map<String, List<String>> endpointBodyRoots) {
        this(endpoints, keys, endpointBodyRoots, Map.of(), Map.of());
    }

    public EsCompletionSchema(
            List<EsSchemaModels.Endpoint> endpoints,
            Map<String, EsSchemaModels.DslNode> keys,
            Map<String, List<String>> endpointBodyRoots,
            Map<String, Map<String, EsSchemaModels.GenericProperty>> requestBodySchemas,
            Map<String, Map<String, EsSchemaModels.GenericProperty>> typeSchemas) {
        this.endpoints = List.copyOf(endpoints);
        this.keys = Map.copyOf(keys);
        this.endpointBodyRoots = Map.copyOf(endpointBodyRoots);
        Map<String, Map<String, EsSchemaModels.GenericProperty>> scoped = new LinkedHashMap<>();
        requestBodySchemas.forEach((requestType, nodes) ->
                scoped.put(requestType, Map.copyOf(nodes)));
        this.requestBodySchemas = Map.copyOf(scoped);
        Map<String, Map<String, EsSchemaModels.GenericProperty>> reusable = new LinkedHashMap<>();
        typeSchemas.forEach((type, nodes) -> reusable.put(type, Map.copyOf(nodes)));
        this.typeSchemas = Map.copyOf(reusable);
    }

    public List<EsSchemaModels.Endpoint> endpoints() {
        return endpoints;
    }

    public Collection<EsSchemaModels.DslNode> keys() {
        return keys.values();
    }

    public EsSchemaModels.DslNode findKey(String key) {
        if (key == null || key.isBlank()) return null;
        return keys.get(key);
    }

    public EsSchemaModels.DslNode findProperty(String parent, String property) {
        if (parent == null || property == null) return null;
        EsSchemaModels.DslNode direct = keys.get(parent + "." + property);
        if (direct != null) return direct;
        return keys.get(property);
    }

    public EsSchemaModels.DslNode findRequestNode(String requestType, String jsonPath) {
        if (requestType == null || requestType.isBlank() || jsonPath == null || jsonPath.isBlank()) return null;
        Navigation navigation = navigate(requestType, jsonPath);
        return navigation == null ? null : navigation.property().node();
    }

    public boolean isSharedQueryDslNode(String requestType, String jsonPath) {
        Navigation navigation = navigate(requestType, jsonPath);
        if (navigation == null) return false;
        return navigation.property().childTypes().stream()
                .anyMatch(type -> type.endsWith(".query_dsl.QueryContainer")
                        || type.equals("QueryContainer"));
    }

    public List<EsSchemaModels.DslNode> requestChildren(String requestType, String parentPath) {
        String parent = normalizeJsonPath(parentPath);
        if (parent.isBlank()) {
            Map<String, EsSchemaModels.GenericProperty> roots = requestBodySchemas.get(requestType);
            return roots == null ? List.of()
                    : roots.values().stream().map(EsSchemaModels.GenericProperty::node).toList();
        }
        Navigation navigation = navigate(requestType, parent);
        if (navigation == null) return List.of();
        EsSchemaModels.GenericProperty property = navigation.property();
        List<String> childTypes;
        if (navigation.dictionaryValueContext()) {
            childTypes = property.dictionaryValueTypes();
        } else if (!property.dictionaryValueTypes().isEmpty()) {
            // At the dictionary itself keys are user-defined, not fixed properties.
            return List.of();
        } else {
            childTypes = property.childTypes();
        }
        LinkedHashMap<String, EsSchemaModels.DslNode> result = new LinkedHashMap<>();
        for (String type : childTypes) {
            Map<String, EsSchemaModels.GenericProperty> children = typeSchemas.get(type);
            if (children == null) continue;
            children.forEach((name, child) -> result.putIfAbsent(name, child.node()));
        }
        return List.copyOf(result.values());
    }

    private static String normalizeJsonPath(String path) {
        return path == null ? "" : path.replace("[]", "").replaceAll("^\\.+|\\.+$", "");
    }

    private Navigation navigate(String requestType, String path) {
        Map<String, EsSchemaModels.GenericProperty> roots = requestBodySchemas.get(requestType);
        if (roots == null) return null;
        String normalized = normalizeJsonPath(path);
        if (normalized.isBlank()) return null;
        String[] segments = normalized.split("\\.");
        EsSchemaModels.GenericProperty current = roots.get(segments[0]);
        if (current == null) return null;
        boolean dictionaryValue = false;
        for (int i = 1; i < segments.length; i++) {
            List<String> candidates;
            if (!current.dictionaryValueTypes().isEmpty() && !dictionaryValue) {
                // The current segment is an arbitrary map key.
                dictionaryValue = true;
                continue;
            }
            candidates = dictionaryValue ? current.dictionaryValueTypes() : current.childTypes();
            current = findTypeProperty(candidates, segments[i]);
            if (current == null) return null;
            dictionaryValue = false;
        }
        return new Navigation(current, dictionaryValue);
    }

    private EsSchemaModels.GenericProperty findTypeProperty(List<String> types, String name) {
        for (String type : types) {
            Map<String, EsSchemaModels.GenericProperty> properties = typeSchemas.get(type);
            if (properties != null && properties.containsKey(name)) return properties.get(name);
        }
        return null;
    }

    private record Navigation(
            EsSchemaModels.GenericProperty property,
            boolean dictionaryValueContext) {}

    public List<EsSchemaModels.DslNode> childrenOf(String parentContext) {
        List<EsSchemaModels.DslNode> result = new ArrayList<>();
        for (EsSchemaModels.DslNode node : keys.values()) {
            if (node.parents().contains(parentContext)
                    || node.parents().contains(normalizeParent(parentContext))
                    || matchesWildcardParent(node, parentContext)) {
                result.add(node);
            }
        }
        return result;
    }

    public List<EsSchemaModels.DslNode> queryDslKeys() {
        return keys.values().stream()
                .filter(node -> "query_dsl".equals(node.category()))
                .toList();
    }

    public List<EsSchemaModels.DslNode> aggregationTypes() {
        return keys.values().stream()
                .filter(node -> node.category() != null && node.category().endsWith("aggregation")
                        && !node.category().equals("aggregation_container"))
                .toList();
    }

    public List<String> bodyRootsForEndpoint(String endpointNameOrPath) {
        if (endpointNameOrPath == null) return List.of();
        List<String> direct = endpointBodyRoots.get(endpointNameOrPath);
        if (direct != null) return direct;
        if (endpointNameOrPath.contains("search")) {
            List<String> search = endpointBodyRoots.get("search");
            if (search != null) return search;
            List<String> fallback = endpointBodyRoots.get("_search");
            if (fallback != null) return fallback;
        }
        return List.of();
    }

    public List<EsSchemaModels.Endpoint> matchEndpoints(String method, String pathEndpoint, String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        String methodUpper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        List<EsSchemaModels.Endpoint> result = new ArrayList<>();
        for (EsSchemaModels.Endpoint endpoint : endpoints) {
            boolean methodOk = methodUpper.isEmpty() || endpoint.methods().contains(methodUpper);
            for (String path : endpoint.paths()) {
                String actionable = actionableEndpoint(path);
                if (!normalizedPrefix.isEmpty()
                        && !actionable.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        && !actionable.toLowerCase(Locale.ROOT).contains(normalizedPrefix)) {
                    continue;
                }
                if (pathEndpoint != null && !pathEndpoint.isBlank()) {
                    if (!actionable.startsWith(pathEndpoint) && !pathEndpoint.startsWith(actionable)) {
                        // still allow prefix match on last segment
                        if (!actionable.contains(normalizedPrefix)) continue;
                    }
                }
                if (methodOk || endpoint.methods().isEmpty()) {
                    result.add(endpoint);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Method-aware fallback for an incomplete URL. Ambiguous candidates deliberately
     * produce no schema rather than selecting the first endpoint.
     */
    public EsSchemaModels.Endpoint findEndpointByPartialPath(
            String method, String fullRequestPath, String actionablePath) {
        String methodUpper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        LinkedHashMap<String, EsSchemaModels.Endpoint> candidates = new LinkedHashMap<>();
        for (EsSchemaModels.Endpoint endpoint : endpoints) {
            if (!methodUpper.isBlank() && !endpoint.methods().isEmpty()
                    && !endpoint.methods().contains(methodUpper)) {
                continue;
            }
            for (String template : endpoint.paths()) {
                if (matchesPartialTemplate(template, fullRequestPath)
                        || matchesActionablePartial(actionableEndpoint(template), actionablePath)) {
                    candidates.put(endpoint.name(), endpoint);
                    break;
                }
            }
        }
        return candidates.size() == 1 ? candidates.values().iterator().next() : null;
    }

    public ResolvedEndpoint resolveEndpoint(String method, String fullRequestPath) {
        if (fullRequestPath == null || fullRequestPath.isBlank()) return null;
        String actualPath = fullRequestPath.split("\\?", 2)[0];
        String methodUpper = method == null ? "" : method.toUpperCase(Locale.ROOT);
        ResolvedEndpoint best = null;
        int bestStaticSegments = -1;
        for (EsSchemaModels.Endpoint endpoint : endpoints) {
            if (!methodUpper.isBlank() && !endpoint.methods().isEmpty()
                    && !endpoint.methods().contains(methodUpper)) {
                continue;
            }
            for (String template : endpoint.paths()) {
                Map<String, String> parameters = matchTemplate(template, actualPath);
                if (parameters == null) continue;
                int staticSegments = (int) segments(template).stream()
                        .filter(segment -> !isParameter(segment))
                        .count();
                if (staticSegments > bestStaticSegments) {
                    best = new ResolvedEndpoint(endpoint, template, parameters);
                    bestStaticSegments = staticSegments;
                }
            }
        }
        return best;
    }

    private static Map<String, String> matchTemplate(String template, String actualPath) {
        List<String> expected = segments(template);
        List<String> actual = segments(actualPath);
        if (expected.size() != actual.size()) return null;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < expected.size(); i++) {
            String segment = expected.get(i);
            if (isParameter(segment)) {
                parameters.put(segment.substring(1, segment.length() - 1), actual.get(i));
            } else if (!segment.equals(actual.get(i))) {
                return null;
            }
        }
        return Map.copyOf(parameters);
    }

    private static boolean matchesPartialTemplate(String template, String actualPath) {
        if (actualPath == null || actualPath.isBlank()) return false;
        String clean = actualPath.split("\\?", 2)[0];
        List<String> expected = segments(template);
        List<String> actual = segments(clean);
        if (actual.isEmpty() || actual.size() > expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            String expectedSegment = expected.get(i);
            if (isParameter(expectedSegment)) continue;
            String actualSegment = actual.get(i);
            boolean last = i == actual.size() - 1;
            if (last) {
                if (!expectedSegment.startsWith(actualSegment)) return false;
            } else if (!expectedSegment.equals(actualSegment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesActionablePartial(String expected, String actual) {
        if (actual == null || actual.isBlank()) return false;
        String normalized = actual.replaceFirst("^/+", "");
        return expected.equals(normalized) || expected.startsWith(normalized);
    }

    private static List<String> segments(String path) {
        if (path == null) return List.of();
        return List.of(path.replaceFirst("^/+", "").split("/")).stream()
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    private static boolean isParameter(String segment) {
        return segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2;
    }

    public record ResolvedEndpoint(
            EsSchemaModels.Endpoint endpoint,
            String pathTemplate,
            Map<String, String> pathParameters) {}

    public static String actionableEndpoint(String pathTemplate) {
        String path = pathTemplate.startsWith("/") ? pathTemplate.substring(1) : pathTemplate;
        String[] parts = path.split("/");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part.startsWith("{") && part.endsWith("}")) continue;
            kept.add(part);
        }
        return String.join("/", kept);
    }

    private static String normalizeParent(String parent) {
        if (parent == null) return "";
        if (parent.endsWith("[]")) return parent.substring(0, parent.length() - 2);
        return parent;
    }

    private static boolean matchesWildcardParent(EsSchemaModels.DslNode node, String parentContext) {
        for (String parent : node.parents()) {
            if (parent.endsWith(".*")) {
                String stem = parent.substring(0, parent.length() - 2);
                if (Objects.equals(stem, parentContext) || parentContext.startsWith(stem + ".")) {
                    return true;
                }
            }
        }
        return false;
    }
}
