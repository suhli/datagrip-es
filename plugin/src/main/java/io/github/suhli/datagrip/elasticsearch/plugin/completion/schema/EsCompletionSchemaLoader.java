package io.github.suhli.datagrip.elasticsearch.plugin.completion.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads generated completion JSON from the plugin classpath. */
public final class EsCompletionSchemaLoader {
    private static final Logger LOG = Logger.getInstance(EsCompletionSchemaLoader.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_RESOURCE = "/completion/es-api-completion.json";
    private static final String DSL_RESOURCE = "/completion/es-dsl-completion.json";

    private static volatile EsCompletionSchema cached;

    private EsCompletionSchemaLoader() {}

    public static EsCompletionSchema get() {
        EsCompletionSchema local = cached;
        if (local != null) return local;
        synchronized (EsCompletionSchemaLoader.class) {
            if (cached == null) {
                cached = load();
            }
            return cached;
        }
    }

    /** Test hook. */
    public static void resetForTests(EsCompletionSchema schema) {
        cached = schema;
    }

    static EsCompletionSchema load() {
        try (InputStream apiIn = resource(API_RESOURCE);
             InputStream dslIn = resource(DSL_RESOURCE)) {
            JsonNode api = JSON.readTree(apiIn);
            JsonNode dsl = JSON.readTree(dslIn);
            return new EsCompletionSchema(
                    parseEndpoints(api), parseKeys(dsl), parseRoots(dsl),
                    parseGenericSchemas(dsl.path("requestBodySchemas")),
                    parseGenericSchemas(dsl.path("typeSchemas")));
        } catch (Exception e) {
            LOG.warn("Failed to load Elasticsearch completion schema; using empty schema", e);
            return new EsCompletionSchema(List.of(), Map.of(), Map.of());
        }
    }

    private static InputStream resource(String name) {
        InputStream in = EsCompletionSchemaLoader.class.getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("Missing resource " + name);
        return in;
    }

    private static List<EsSchemaModels.Endpoint> parseEndpoints(JsonNode api) {
        List<EsSchemaModels.Endpoint> result = new ArrayList<>();
        for (JsonNode obj : api.path("endpoints")) {
            result.add(new EsSchemaModels.Endpoint(
                    text(obj, "name"),
                    stringList(obj.get("methods")),
                    stringList(obj.get("paths")),
                    parseQueryParams(obj.get("queryParams")),
                    text(obj, "requestBodyType"),
                    text(obj, "documentation"),
                    text(obj, "minVersion"),
                    text(obj, "deprecatedVersion"),
                    obj.path("deprecated").asBoolean(false)));
        }
        return result;
    }

    private static List<EsSchemaModels.QueryParam> parseQueryParams(JsonNode array) {
        List<EsSchemaModels.QueryParam> result = new ArrayList<>();
        if (array == null || !array.isArray()) return result;
        for (JsonNode obj : array) {
            result.add(new EsSchemaModels.QueryParam(
                    text(obj, "name"),
                    text(obj, "type"),
                    stringList(obj.get("enumValues")),
                    text(obj, "description"),
                    text(obj, "minVersion"),
                    text(obj, "deprecatedVersion"),
                    obj.path("deprecated").asBoolean(false)));
        }
        return result;
    }

    private static Map<String, EsSchemaModels.DslNode> parseKeys(JsonNode dsl) {
        Map<String, EsSchemaModels.DslNode> result = new LinkedHashMap<>();
        JsonNode keys = dsl.path("keys");
        if (!keys.isObject()) return result;
        keys.fieldNames().forEachRemaining(fieldName -> {
            JsonNode obj = keys.get(fieldName);
            result.put(fieldName, new EsSchemaModels.DslNode(
                    text(obj, "key").isEmpty() ? fieldName : text(obj, "key"),
                    text(obj, "category"),
                    stringList(obj.get("parents")),
                    stringList(obj.get("children")),
                    text(obj, "valueType"),
                    obj.path("fieldReference").asBoolean(false),
                    stringList(obj.get("enumValues")),
                    text(obj, "snippet"),
                    text(obj, "description"),
                    text(obj, "minVersion"),
                    text(obj, "deprecatedVersion"),
                    obj.path("deprecated").asBoolean(false),
                    obj.path("priority").asInt(50)));
        });
        return result;
    }

    private static Map<String, List<String>> parseRoots(JsonNode dsl) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        JsonNode roots = dsl.path("endpointBodyRoots");
        if (!roots.isObject()) return result;
        roots.fieldNames().forEachRemaining(fieldName ->
                result.put(fieldName, stringList(roots.get(fieldName))));
        return result;
    }

    private static Map<String, Map<String, EsSchemaModels.GenericProperty>> parseGenericSchemas(
            JsonNode schemas) {
        Map<String, Map<String, EsSchemaModels.GenericProperty>> result = new LinkedHashMap<>();
        if (!schemas.isObject()) return result;
        schemas.fieldNames().forEachRemaining(type -> {
            Map<String, EsSchemaModels.GenericProperty> nodes = new LinkedHashMap<>();
            JsonNode schema = schemas.path(type);
            schema.fieldNames().forEachRemaining(name -> {
                JsonNode property = schema.path(name);
                nodes.put(name, new EsSchemaModels.GenericProperty(
                        parseDslNode(property.path("node"), name),
                        stringList(property.get("childTypes")),
                        stringList(property.get("dictionaryValueTypes"))));
            });
            result.put(type, Map.copyOf(nodes));
        });
        return result;
    }

    private static EsSchemaModels.DslNode parseDslNode(JsonNode obj, String fallbackKey) {
        return new EsSchemaModels.DslNode(
                text(obj, "key").isEmpty() ? fallbackKey : text(obj, "key"),
                text(obj, "category"),
                stringList(obj.get("parents")),
                stringList(obj.get("children")),
                text(obj, "valueType"),
                obj.path("fieldReference").asBoolean(false),
                stringList(obj.get("enumValues")),
                text(obj, "snippet"),
                text(obj, "description"),
                text(obj, "minVersion"),
                text(obj, "deprecatedVersion"),
                obj.path("deprecated").asBoolean(false),
                obj.path("priority").asInt(50));
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode obj, String field) {
        JsonNode value = obj.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
