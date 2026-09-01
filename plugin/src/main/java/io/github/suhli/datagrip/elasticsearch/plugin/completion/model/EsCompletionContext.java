package io.github.suhli.datagrip.elasticsearch.plugin.completion.model;

import java.util.List;
import java.util.Objects;

/** Immutable completion context for a caret position. */
public final class EsCompletionContext {
    private final String method;
    private final String path;
    private final String endpoint;
    private final List<String> indices;
    private final EsCaretLocation location;
    private final EsExpectedKind expectedKind;
    private final String prefix;
    private final String jsonPath;
    private final String currentProperty;
    private final String parentProperty;
    private final boolean insideString;
    private final boolean replacingQuotedValue;
    private final String queryParameterName;
    private final String datasourceId;
    private final String esVersion;
    private final int requestStart;
    private final int requestEnd;
    private final int caretOffset;

    private EsCompletionContext(Builder builder) {
        this.method = builder.method;
        this.path = builder.path;
        this.endpoint = builder.endpoint;
        this.indices = List.copyOf(builder.indices);
        this.location = builder.location;
        this.expectedKind = builder.expectedKind;
        this.prefix = builder.prefix;
        this.jsonPath = builder.jsonPath;
        this.currentProperty = builder.currentProperty;
        this.parentProperty = builder.parentProperty;
        this.insideString = builder.insideString;
        this.replacingQuotedValue = builder.replacingQuotedValue;
        this.queryParameterName = builder.queryParameterName;
        this.datasourceId = builder.datasourceId;
        this.esVersion = builder.esVersion;
        this.requestStart = builder.requestStart;
        this.requestEnd = builder.requestEnd;
        this.caretOffset = builder.caretOffset;
    }

    public String method() { return method; }
    public String path() { return path; }
    public String endpoint() { return endpoint; }
    public List<String> indices() { return indices; }
    public EsCaretLocation location() { return location; }
    public EsExpectedKind expectedKind() { return expectedKind; }
    public String prefix() { return prefix; }
    public String jsonPath() { return jsonPath; }
    public String currentProperty() { return currentProperty; }
    public String parentProperty() { return parentProperty; }
    public boolean insideString() { return insideString; }
    public boolean replacingQuotedValue() { return replacingQuotedValue; }
    public String queryParameterName() { return queryParameterName; }
    public String datasourceId() { return datasourceId; }
    public String esVersion() { return esVersion; }
    public int requestStart() { return requestStart; }
    public int requestEnd() { return requestEnd; }
    public int caretOffset() { return caretOffset; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String method = "";
        private String path = "";
        private String endpoint = "";
        private List<String> indices = List.of();
        private EsCaretLocation location = EsCaretLocation.URL;
        private EsExpectedKind expectedKind = EsExpectedKind.UNKNOWN;
        private String prefix = "";
        private String jsonPath = "";
        private String currentProperty = "";
        private String parentProperty = "";
        private boolean insideString;
        private boolean replacingQuotedValue;
        private String queryParameterName = "";
        private String datasourceId = "";
        private String esVersion = "";
        private int requestStart;
        private int requestEnd;
        private int caretOffset;

        public Builder method(String method) { this.method = Objects.requireNonNullElse(method, ""); return this; }
        public Builder path(String path) { this.path = Objects.requireNonNullElse(path, ""); return this; }
        public Builder endpoint(String endpoint) { this.endpoint = Objects.requireNonNullElse(endpoint, ""); return this; }
        public Builder indices(List<String> indices) { this.indices = indices == null ? List.of() : indices; return this; }
        public Builder location(EsCaretLocation location) { this.location = location; return this; }
        public Builder expectedKind(EsExpectedKind expectedKind) { this.expectedKind = expectedKind; return this; }
        public Builder prefix(String prefix) { this.prefix = Objects.requireNonNullElse(prefix, ""); return this; }
        public Builder jsonPath(String jsonPath) { this.jsonPath = Objects.requireNonNullElse(jsonPath, ""); return this; }
        public Builder currentProperty(String currentProperty) { this.currentProperty = Objects.requireNonNullElse(currentProperty, ""); return this; }
        public Builder parentProperty(String parentProperty) { this.parentProperty = Objects.requireNonNullElse(parentProperty, ""); return this; }
        public Builder insideString(boolean insideString) { this.insideString = insideString; return this; }
        public Builder replacingQuotedValue(boolean replacingQuotedValue) { this.replacingQuotedValue = replacingQuotedValue; return this; }
        public Builder queryParameterName(String queryParameterName) { this.queryParameterName = Objects.requireNonNullElse(queryParameterName, ""); return this; }
        public Builder datasourceId(String datasourceId) { this.datasourceId = Objects.requireNonNullElse(datasourceId, ""); return this; }
        public Builder esVersion(String esVersion) { this.esVersion = Objects.requireNonNullElse(esVersion, ""); return this; }
        public Builder requestStart(int requestStart) { this.requestStart = requestStart; return this; }
        public Builder requestEnd(int requestEnd) { this.requestEnd = requestEnd; return this; }
        public Builder caretOffset(int caretOffset) { this.caretOffset = caretOffset; return this; }

        public EsCompletionContext build() {
            return new EsCompletionContext(this);
        }
    }
}
