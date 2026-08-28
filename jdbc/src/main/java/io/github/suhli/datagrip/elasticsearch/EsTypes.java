package io.github.suhli.datagrip.elasticsearch;

import java.sql.Types;

public final class EsTypes {
    private EsTypes() {}

    public static int jdbcType(String esType) {
        if (esType == null) return Types.JAVA_OBJECT;
        return switch (esType) {
            case "boolean" -> Types.BOOLEAN;
            case "byte" -> Types.TINYINT;
            case "short" -> Types.SMALLINT;
            case "integer", "unsigned_long" -> Types.INTEGER;
            case "long" -> Types.BIGINT;
            case "half_float", "float" -> Types.FLOAT;
            case "double", "scaled_float" -> Types.DOUBLE;
            case "date", "date_nanos" -> Types.TIMESTAMP;
            case "binary" -> Types.BINARY;
            case "object", "nested", "flattened", "geo_point", "geo_shape", "dense_vector",
                    "sparse_vector", "rank_features" -> Types.JAVA_OBJECT;
            case "text", "keyword", "constant_keyword", "wildcard", "ip", "version",
                    "completion", "token_count", "alias", "_source" -> Types.VARCHAR;
            default -> Types.OTHER;
        };
    }

    public static String jdbcTypeName(int type) {
        return switch (type) {
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.FLOAT -> "FLOAT";
            case Types.DOUBLE -> "DOUBLE";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.BINARY -> "BINARY";
            case Types.VARCHAR -> "VARCHAR";
            case Types.JAVA_OBJECT -> "JAVA_OBJECT";
            default -> "OTHER";
        };
    }
}
