package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonResultMapper {
    static final int MAX_RAW_RESPONSE_BYTES = 5 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonResultMapper() {}

    public static TabularResult map(String json) throws JsonProcessingException {
        return mapResponse(json).tabular();
    }

    public static MappedResponse mapResponse(String json) throws JsonProcessingException {
        JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        if (root.isObject() && root.has("hits") && root.path("hits").isObject()) {
            JsonNode hits = root.path("hits").path("hits");
            if (hits.isArray()) {
                List<Map<String, Object>> records = searchHits(hits);
                return new MappedResponse(tabular(records), json, true);
            }
        }
        List<Map<String, Object>> records = new ArrayList<>();
        collectBuckets("", root.path("aggregations"), records);
        if (records.isEmpty()) {
            if (root.isArray()) {
                root.forEach(node -> addRecord(records, flattenRecord(node)));
            } else if (root.isObject()) {
                addRecord(records, flattenRecord(root));
            } else {
                addRecord(records, flattenRecord(root));
            }
        }
        TabularResult structured = tabular(records);
        if (isMeaningfulStructure(structured)) {
            return new MappedResponse(structured, json, true);
        }
        return new MappedResponse(rawFallback(json), json, false);
    }

    /** Legacy helper retained for unstructured responses. */
    public static TabularResult mapWithRawResponse(String json) throws JsonProcessingException {
        return mapResponse(json).tabular();
    }

    private static void addRecord(List<Map<String, Object>> records, Map<String, Object> record) {
        if (!record.isEmpty()) records.add(record);
    }

    private static boolean isMeaningfulStructure(TabularResult result) {
        if (!result.rows().isEmpty()) return true;
        return result.columns().stream().anyMatch(column ->
                !column.label().equals("value") && !column.label().equals("_response"));
    }

    private static TabularResult rawFallback(String json) {
        String body = truncateRaw(json);
        return new TabularResult(
                List.of(new TabularResult.Column("_response", Types.LONGVARCHAR, "JSON")),
                List.of(List.of(body)),
                json,
                false);
    }

    static String truncateRaw(String json) {
        if (json == null) return null;
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_RAW_RESPONSE_BYTES) return json;
        return "<raw response omitted: " + bytes.length + " bytes>";
    }

    private static List<Map<String, Object>> searchHits(JsonNode hits) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            copyIfPresent(hit, "_index", row);
            copyIfPresent(hit, "_id", row);
            copyIfPresent(hit, "_score", row);
            flattenSource("", hit.path("_source"), row);
            JsonNode fields = hit.path("fields");
            if (fields.isObject()) flattenFields("fields", fields, row);
            records.add(row);
        }
        return records;
    }

    private static void copyIfPresent(JsonNode source, String field, Map<String, Object> target) {
        if (source.has(field)) putUnique(target, field, scalarOrJson(source.get(field)));
    }

    private static void flattenSource(String prefix, JsonNode node, Map<String, Object> target) {
        if (!node.isObject()) {
            if (!node.isMissingNode() && !node.isNull()) {
                putUnique(target, prefix.isEmpty() ? "value" : prefix, scalarOrJson(node));
            }
            return;
        }
        node.properties().forEach(entry -> {
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) flattenSource(name, value, target);
            else putUnique(target, name, scalarOrJson(value));
        });
    }

    private static void flattenFields(String prefix, JsonNode node, Map<String, Object> target) {
        if (!node.isObject()) return;
        node.properties().forEach(entry -> {
            String name = prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) flattenFields(name, value, target);
            else putUnique(target, name, scalarOrJson(value));
        });
    }

    private static void putUnique(Map<String, Object> target, String key, Object value) {
        if (target.containsKey(key)) {
            throw new IllegalStateException("Duplicate flattened column: " + key);
        }
        target.put(key, value);
    }

    private static void collectBuckets(String prefix, JsonNode node, List<Map<String, Object>> output) {
        if (!node.isObject()) return;
        node.properties().forEach(aggregation -> {
            JsonNode buckets = aggregation.getValue().path("buckets");
            if (buckets.isArray()) {
                for (JsonNode bucket : buckets) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String name = prefix.isEmpty() ? aggregation.getKey() : prefix + "." + aggregation.getKey();
                    row.put("aggregation", name);
                    row.put("key", scalarOrJson(bucket.path("key")));
                    if (bucket.has("doc_count")) row.put("doc_count", bucket.path("doc_count").longValue());
                    flattenMetrics("", bucket, row);
                    output.add(row);
                }
            }
        });
    }

    private static void flattenMetrics(String prefix, JsonNode node, Map<String, Object> row) {
        node.properties().forEach(entry -> {
            if ("key".equals(entry.getKey()) || "key_as_string".equals(entry.getKey())
                    || "doc_count".equals(entry.getKey())) return;
            JsonNode value = entry.getValue();
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (value.isObject() && value.has("value")) {
                row.put(name, scalarOrJson(value.get("value")));
            } else if (value.isObject() && !value.has("buckets")) {
                flattenSource(name, value, row);
            } else if (value.has("buckets")) {
                row.put(name, json(value.get("buckets")));
            }
        });
    }

    private static Map<String, Object> flattenRecord(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        flattenSource("", node, row);
        if (row.isEmpty() && !node.isObject()) row.put("value", scalarOrJson(node));
        return row;
    }

    private static Object scalarOrJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) {
            if (node.canConvertToLong()) return node.longValue();
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) return node.decimalValue();
        return json(node);
    }

    private static String json(JsonNode node) {
        try { return JSON.writeValueAsString(node); }
        catch (JsonProcessingException e) { return node.toString(); }
    }

    private static TabularResult tabular(List<Map<String, Object>> records) {
        Set<String> labels = new LinkedHashSet<>();
        records.forEach(row -> labels.addAll(row.keySet()));
        List<TabularResult.Column> columns = labels.stream().map(label -> {
            int type = unionType(records, label);
            return new TabularResult.Column(label, type, EsTypes.jdbcTypeName(type));
        }).toList();
        List<List<Object>> rows = records.stream()
                .map(record -> labels.stream().map(record::get).toList())
                .toList();
        return new TabularResult(columns, rows);
    }

    private static int unionType(List<Map<String, Object>> records, String label) {
        Integer merged = null;
        for (Map<String, Object> record : records) {
            Object value = record.get(label);
            if (value == null) continue;
            int type = typeOf(value);
            merged = merged == null ? type : mergeTypes(merged, type);
        }
        return merged == null ? Types.VARCHAR : merged;
    }

    private static int mergeTypes(int left, int right) {
        if (left == right) return left;
        if (isStringType(left) || isStringType(right)) return Types.VARCHAR;
        if (isObjectType(left) || isObjectType(right)) return Types.VARCHAR;
        if (isIntegerFamily(left) && isIntegerFamily(right)) {
            if (left == Types.BIGINT || right == Types.BIGINT) return Types.BIGINT;
            return Types.BIGINT;
        }
        if (isNumeric(left) && isNumeric(right)) {
            if (left == Types.DECIMAL || right == Types.DECIMAL) return Types.DECIMAL;
            if (left == Types.DOUBLE || right == Types.DOUBLE
                    || left == Types.FLOAT || right == Types.FLOAT) {
                return Types.DOUBLE;
            }
            return Types.DECIMAL;
        }
        return Types.VARCHAR;
    }

    private static boolean isIntegerFamily(int type) {
        return type == Types.TINYINT || type == Types.SMALLINT
                || type == Types.INTEGER || type == Types.BIGINT;
    }

    private static boolean isNumeric(int type) {
        return isIntegerFamily(type) || type == Types.FLOAT || type == Types.DOUBLE || type == Types.DECIMAL;
    }

    private static boolean isStringType(int type) {
        return type == Types.VARCHAR || type == Types.LONGVARCHAR || type == Types.CHAR;
    }

    private static boolean isObjectType(int type) {
        return type == Types.JAVA_OBJECT || type == Types.OTHER;
    }

    private static int typeOf(Object value) {
        if (value instanceof Boolean) return Types.BOOLEAN;
        if (value instanceof Byte) return Types.TINYINT;
        if (value instanceof Short) return Types.SMALLINT;
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof Long) return Types.BIGINT;
        if (value instanceof BigInteger) return Types.DECIMAL;
        if (value instanceof Float) return Types.FLOAT;
        if (value instanceof Double) return Types.DOUBLE;
        if (value instanceof BigDecimal) return Types.DECIMAL;
        if (value instanceof String) return Types.VARCHAR;
        return Types.VARCHAR;
    }

    public record MappedResponse(TabularResult tabular, String rawBody, boolean structured) {}
}
