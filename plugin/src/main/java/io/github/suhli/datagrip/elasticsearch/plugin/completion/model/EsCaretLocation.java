package io.github.suhli.datagrip.elasticsearch.plugin.completion.model;

/** Location of the caret within a request. */
public enum EsCaretLocation {
    METHOD,
    URL,
    QUERY_STRING,
    BODY
}
