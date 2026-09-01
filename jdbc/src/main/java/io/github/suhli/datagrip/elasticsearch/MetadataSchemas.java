package io.github.suhli.datagrip.elasticsearch;

import java.sql.Types;
import java.util.List;

/** JDBC DatabaseMetaData column schemas with correct SQL types. */
final class MetadataSchemas {
    record Column(String name, int jdbcType, String typeName) {}

    static final List<Column> CATALOGS = List.of(
            new Column("TABLE_CAT", Types.VARCHAR, "VARCHAR"));

    static final List<Column> SCHEMAS = List.of(
            new Column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_CATALOG", Types.VARCHAR, "VARCHAR"));

    static final List<Column> TABLE_TYPES = List.of(
            new Column("TABLE_TYPE", Types.VARCHAR, "VARCHAR"));

    static final List<Column> TABLES = List.of(
            new Column("TABLE_CAT", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_TYPE", Types.VARCHAR, "VARCHAR"),
            new Column("REMARKS", Types.VARCHAR, "VARCHAR"),
            new Column("TYPE_CAT", Types.VARCHAR, "VARCHAR"),
            new Column("TYPE_SCHEM", Types.VARCHAR, "VARCHAR"),
            new Column("TYPE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("SELF_REFERENCING_COL_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("REF_GENERATION", Types.VARCHAR, "VARCHAR"));

    static final List<Column> COLUMNS = List.of(
            new Column("TABLE_CAT", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
            new Column("TABLE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("COLUMN_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("DATA_TYPE", Types.INTEGER, "INTEGER"),
            new Column("TYPE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("COLUMN_SIZE", Types.INTEGER, "INTEGER"),
            new Column("BUFFER_LENGTH", Types.INTEGER, "INTEGER"),
            new Column("DECIMAL_DIGITS", Types.INTEGER, "INTEGER"),
            new Column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER"),
            new Column("NULLABLE", Types.INTEGER, "INTEGER"),
            new Column("REMARKS", Types.VARCHAR, "VARCHAR"),
            new Column("COLUMN_DEF", Types.VARCHAR, "VARCHAR"),
            new Column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER"),
            new Column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER"),
            new Column("CHAR_OCTET_LENGTH", Types.INTEGER, "INTEGER"),
            new Column("ORDINAL_POSITION", Types.INTEGER, "INTEGER"),
            new Column("IS_NULLABLE", Types.VARCHAR, "VARCHAR"),
            new Column("SCOPE_CATALOG", Types.VARCHAR, "VARCHAR"),
            new Column("SCOPE_SCHEMA", Types.VARCHAR, "VARCHAR"),
            new Column("SCOPE_TABLE", Types.VARCHAR, "VARCHAR"),
            new Column("SOURCE_DATA_TYPE", Types.INTEGER, "INTEGER"),
            new Column("IS_AUTOINCREMENT", Types.VARCHAR, "VARCHAR"),
            new Column("IS_GENERATEDCOLUMN", Types.VARCHAR, "VARCHAR"));

    static final List<Column> TYPE_INFO = List.of(
            new Column("TYPE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("DATA_TYPE", Types.INTEGER, "INTEGER"),
            new Column("PRECISION", Types.INTEGER, "INTEGER"),
            new Column("LITERAL_PREFIX", Types.VARCHAR, "VARCHAR"),
            new Column("LITERAL_SUFFIX", Types.VARCHAR, "VARCHAR"),
            new Column("CREATE_PARAMS", Types.VARCHAR, "VARCHAR"),
            new Column("NULLABLE", Types.INTEGER, "INTEGER"),
            new Column("CASE_SENSITIVE", Types.BOOLEAN, "BOOLEAN"),
            new Column("SEARCHABLE", Types.INTEGER, "INTEGER"),
            new Column("UNSIGNED_ATTRIBUTE", Types.BOOLEAN, "BOOLEAN"),
            new Column("FIXED_PREC_SCALE", Types.BOOLEAN, "BOOLEAN"),
            new Column("AUTO_INCREMENT", Types.BOOLEAN, "BOOLEAN"),
            new Column("LOCAL_TYPE_NAME", Types.VARCHAR, "VARCHAR"),
            new Column("MINIMUM_SCALE", Types.INTEGER, "INTEGER"),
            new Column("MAXIMUM_SCALE", Types.INTEGER, "INTEGER"),
            new Column("SQL_DATA_TYPE", Types.INTEGER, "INTEGER"),
            new Column("SQL_DATETIME_SUB", Types.INTEGER, "INTEGER"),
            new Column("NUM_PREC_RADIX", Types.INTEGER, "INTEGER"));

    private MetadataSchemas() {}
}
