package io.github.suhli.datagrip.elasticsearch.plugin.completion.model;

/** What completion expects at the caret. */
public enum EsExpectedKind {
    HTTP_METHOD,
    ENDPOINT,
    PATH_SEGMENT,
    INDEX,
    QUERY_PARAMETER,
    QUERY_PARAMETER_VALUE,
    BODY_KEY,
    QUERY_DSL,
    AGGREGATION_TYPE,
    AGGREGATION_NAME,
    FIELD_KEY,
    FIELD_VALUE,
    ENUM_VALUE,
    BOOLEAN_VALUE,
    NUMBER_VALUE,
    USER_DEFINED_NAME,
    UNKNOWN
}
