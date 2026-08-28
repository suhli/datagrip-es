package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JsonResultMapper {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonResultMapper() {}

    public static TabularResult map(String json) throws JsonProcessingException {
        JsonNode root = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        List<Map<String, Object>> records;
        JsonNode hits = root.path("hits").path("hits");
        if (hits.isArray()) records = searchHits(hits);
        else {
            records = new ArrayList<>();
            collectBuckets("", root.path("aggregations"), records);
            if (records.isEmpty()) {
                if (root.isArray()) root.forEach(node -> records.add(flattenRecord(node)));
                else records.add(flattenRecord(root));
            }
        }
        return tabular(records);
    }

    private static List<Map<String, Object>> searchHits(JsonNode hits) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            copyIfPresent(hit, "_index", row);
            copyIfPresent(hit, "_id", row);
            copyIfPresent(hit, "_score", row);
            flatten("", hit.path("_source"), row);
            JsonNode fields = hit.path("fields");
            if (fields.isObject()) flatten("", fields, row);
            records.add(row);
        }
        return records;
    }

    private static void copyIfPresent(JsonNode source, String field, Map<String, Object> target) {
        if (source.has(field)) target.put(field, scalarOrJson(source.get(field)));
    }

    private static void collectBuckets(String prefix, JsonNode node, List<Map<String, Object>> output) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(aggregation -> {
            JsonNode buckets = aggregation.getValue().path("buckets");
            if (buckets.isArray()) {
                for (JsonNode bucket : buckets) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String name = prefix.isEmpty() ? aggregation.getKey() : prefix + "." + aggregation.getKey();
                    row.put(name + ".key", scalarOrJson(bucket.path("key")));
                    if (bucket.has("doc_count")) row.put(name + ".doc_count", bucket.path("doc_count").longValue());
                    flattenMetrics(name, bucket, row);
                    output.add(row);
                }
            }
        });
    }

    private static void flattenMetrics(String prefix, JsonNode node, Map<String, Object> row) {
        node.fields().forEachRemaining(entry -> {
            if ("key".equals(entry.getKey()) || "doc_count".equals(entry.getKey())) return;
            JsonNode value = entry.getValue();
            if (value.isObject() && value.has("value")) {
                row.put(prefix + "." + entry.getKey(), scalarOrJson(value.get("value")));
            } else if (value.isObject() && !value.has("buckets")) {
                flatten(prefix + "." + entry.getKey(), value, row);
            } else if (value.has("buckets")) {
                row.put(prefix + "." + entry.getKey(), json(value.get("buckets")));
            }
        });
    }

    private static Map<String, Object> flattenRecord(JsonNode node) {
        Map<String, Object> row = new LinkedHashMap<>();
        flatten("", node, row);
        if (row.isEmpty() && !node.isObject()) row.put("value", scalarOrJson(node));
        return row;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> target) {
        if (!node.isObject()) {
            target.put(prefix.isEmpty() ? "value" : prefix, scalarOrJson(node));
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) flatten(name, value, target);
            else target.put(name, scalarOrJson(value));
        });
    }

    private static Object scalarOrJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.canConvertToLong() ? node.longValue() : node.bigIntegerValue();
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
            Object sample = records.stream().map(row -> row.get(label)).filter(v -> v != null).findFirst().orElse(null);
            int type = typeOf(sample);
            return new TabularResult.Column(label, type, EsTypes.jdbcTypeName(type));
        }).toList();
        List<List<Object>> rows = records.stream()
                .map(record -> labels.stream().map(record::get).toList())
                .toList();
        return new TabularResult(columns, rows);
    }

    private static int typeOf(Object value) {
        if (value instanceof Boolean) return Types.BOOLEAN;
        if (value instanceof Byte) return Types.TINYINT;
        if (value instanceof Short) return Types.SMALLINT;
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof Long || value instanceof java.math.BigInteger) return Types.BIGINT;
        if (value instanceof Float) return Types.FLOAT;
        if (value instanceof Double || value instanceof BigDecimal) return Types.DOUBLE;
        return Types.VARCHAR;
    }
}
