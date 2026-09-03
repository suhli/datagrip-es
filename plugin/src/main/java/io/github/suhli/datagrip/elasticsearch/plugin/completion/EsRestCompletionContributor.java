package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataService;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataRegistrar;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCaretLocation;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCompletionContext;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchemaLoader;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaAvailability;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestFileDetector;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestLanguage;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.database.psi.DbDataSource;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.PatternCondition;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class EsRestCompletionContributor extends CompletionContributor {
    public EsRestCompletionContributor() {
        CompletionProvider<CompletionParameters> provider = new CompletionProvider<>() {
            @Override
            protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result) {
                fill(parameters, result);
            }
        };
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile().with(
                        new PatternCondition<>("esRestConsoleFile") {
                            @Override
                            public boolean accepts(@NotNull PsiFile file, ProcessingContext context) {
                                return file.getLanguage().isKindOf(EsRestLanguage.INSTANCE)
                                        || EsRestFileDetector.isEsRestFile(file);
                            }
                        })),
                provider);
    }

    static void fill(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        if (!file.getLanguage().isKindOf(EsRestLanguage.INSTANCE)
                && !EsRestFileDetector.isEsRestFile(file)) {
            return;
        }
        Project project = file.getProject();
        EsCompletionSchema schema = EsCompletionSchemaLoader.get();
        DbDataSource dataSource = EsCompletionDataSourceUtil.findDataSource(parameters);
        if (dataSource != null) {
            EsCompletionDataSourceUtil.bindDataSource(file, dataSource);
            EsCompletionMetadataRegistrar.ensureRegistered(project, dataSource);
        }
        String datasourceId = EsCompletionDataSourceUtil.resolveDatasourceId(parameters);
        String version = EsCompletionDataSourceUtil.resolveVersion(parameters);
        EsCompletionContextResolver resolver = new EsCompletionContextResolver(schema);
        EsCompletionContext ctx = resolver.resolve(
                file, parameters.getOffset(), datasourceId, version);
        EsCompletionMetadataSnapshot snapshot = EsCompletionMetadataService.getInstance(project)
                .snapshotForIndices(datasourceId, dataSource, ctx.indices());
        if (ctx.esVersion().isBlank() && !snapshot.esVersion().isBlank()) {
            ctx = resolver.resolve(file, parameters.getOffset(), datasourceId, snapshot.esVersion());
        }
        CompletionResultSet filtered = result.withPrefixMatcher(ctx.prefix() == null ? "" : ctx.prefix());
        produceCompletions(ctx, schema, snapshot, filtered::addElement);
    }

    /** Test hook that exercises the same lookup generation path as {@link #fill}. */
    static List<String> lookupStringsForTest(PsiFile file, int offset) {
        return lookupStringsForTest(file, offset, "");
    }

    static List<String> lookupStringsForTest(PsiFile file, int offset, String datasourceId) {
        EsCompletionSchema schema = EsCompletionSchemaLoader.get();
        EsCompletionContext ctx = new EsCompletionContextResolver(schema).resolve(file, offset, datasourceId, "");
        EsCompletionMetadataSnapshot snapshot = EsCompletionMetadataService.getInstance(file.getProject())
                .snapshotForIndices(datasourceId, ctx.indices());
        List<String> items = new ArrayList<>();
        produceCompletions(ctx, schema, snapshot, element -> items.add(element.getLookupString()));
        return items;
    }

    private static void produceCompletions(
            EsCompletionContext ctx,
            EsCompletionSchema schema,
            EsCompletionMetadataSnapshot snapshot,
            Consumer<LookupElement> sink) {
        switch (ctx.expectedKind()) {
            case HTTP_METHOD -> addHttpMethods(sink, ctx);
            case ENDPOINT, PATH_SEGMENT -> addEndpoints(sink, schema, ctx);
            case INDEX -> addIndices(sink, snapshot, ctx);
            case QUERY_PARAMETER -> addQueryParams(sink, schema, ctx);
            case QUERY_PARAMETER_VALUE, ENUM_VALUE -> addEnumValues(sink, schema, ctx);
            case BOOLEAN_VALUE -> addBooleans(sink, ctx);
            case BODY_KEY -> addBodyKeys(sink, schema, ctx);
            case QUERY_DSL -> addQueryDsl(sink, schema, ctx);
            case AGGREGATION_TYPE -> addAggregations(sink, schema, ctx);
            case AGGREGATION_NAME, USER_DEFINED_NAME -> {
                // intentionally no aggregation-type spam
            }
            case FIELD_KEY, FIELD_VALUE -> addFields(sink, snapshot, ctx);
            default -> {
                if (ctx.location() == EsCaretLocation.METHOD) {
                    addHttpMethods(sink, ctx);
                } else if (ctx.location() == EsCaretLocation.URL) {
                    addIndices(sink, snapshot, ctx);
                    addEndpoints(sink, schema, ctx);
                }
            }
        }
    }

    private static void addHttpMethods(Consumer<LookupElement> sink, EsCompletionContext ctx) {
        String prefix = ctx.prefix() == null ? "" : ctx.prefix().toUpperCase(Locale.ROOT);
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")) {
            if (!prefix.isEmpty() && !method.startsWith(prefix)) continue;
            sink.accept(EsLookupFactory.httpMethod(method));
        }
    }

    private static void addEndpoints(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        Set<String> seen = new LinkedHashSet<>();
        for (EsSchemaModels.Endpoint endpoint : schema.matchEndpoints(ctx.method(), ctx.endpoint(), ctx.prefix())) {
            if (!EsSchemaAvailability.isAvailable(endpoint, ctx.esVersion())) continue;
            String key = EsCompletionSchemaPaths.primaryPath(endpoint);
            if (!seen.add(key)) continue;
            sink.accept(EsLookupFactory.endpoint(endpoint, ctx));
        }
    }

    private static void addIndices(
            Consumer<LookupElement> sink, EsCompletionMetadataSnapshot snapshot, EsCompletionContext ctx) {
        String prefix = ctx.prefix() == null ? "" : ctx.prefix().toLowerCase(Locale.ROOT);
        for (EsCompletionMetadataSnapshot.IndexObject index : snapshot.indices()) {
            if (!prefix.isEmpty() && !index.name().toLowerCase(Locale.ROOT).startsWith(prefix)) continue;
            sink.accept(EsLookupFactory.index(index));
        }
    }

    private static void addQueryParams(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        EsSchemaModels.Endpoint endpoint = resolveEndpoint(schema, ctx);
        if (endpoint == null) return;
        for (EsSchemaModels.QueryParam param : endpoint.queryParams()) {
            if (!EsSchemaAvailability.isAvailable(param, ctx.esVersion())) continue;
            sink.accept(EsLookupFactory.queryParam(param, ctx.insideString(), ctx.esVersion()));
        }
    }

    private static void addEnumValues(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        List<String> values = new ArrayList<>();
        if (ctx.expectedKind() == EsExpectedKind.QUERY_PARAMETER_VALUE) {
            EsSchemaModels.Endpoint endpoint = resolveEndpoint(schema, ctx);
            if (endpoint != null) {
                for (EsSchemaModels.QueryParam param : endpoint.queryParams()) {
                    if (param.name().equals(ctx.queryParameterName())
                            && EsSchemaAvailability.isAvailable(param, ctx.esVersion())) {
                        values.addAll(param.enumValues());
                    }
                }
            }
        } else {
            String leaf = leaf(ctx.jsonPath());
            EsSchemaModels.DslNode node = schema.findProperty(ctx.parentProperty(), leaf);
            if (EsSchemaAvailability.isAvailable(node, ctx.esVersion())) values.addAll(node.enumValues());
            if (values.isEmpty() && "operator".equals(leaf)) {
                values.addAll(List.of("and", "or"));
            }
        }
        for (String value : values) {
            sink.accept(EsLookupFactory.enumValue(value, "enum value", ctx.insideString()));
        }
    }

    private static void addBooleans(Consumer<LookupElement> sink, EsCompletionContext ctx) {
        sink.accept(EsLookupFactory.enumValue("true", "boolean", false));
        sink.accept(EsLookupFactory.enumValue("false", "boolean", false));
    }

    private static void addBodyKeys(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        if (ctx.jsonPath().isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            EsSchemaModels.Endpoint endpoint = resolveEndpoint(schema, ctx);
            String endpointKey = endpoint == null ? endpointKey(ctx) : endpoint.name();
            for (String key : schema.bodyRootsForEndpoint(endpointKey)) {
                if (!seen.add(key)) continue;
                EsSchemaModels.DslNode node = schema.findKey(key);
                if (node == null) {
                    sink.accept(EsLookupFactory.dslKey(new EsSchemaModels.DslNode(
                            key, "search_body", List.of("search"), List.of(), "object", false,
                            List.of(), null, "Search body",
                            null, null, false, 100), ctx));
                } else if (EsSchemaAvailability.isAvailable(node, ctx.esVersion())) {
                    sink.accept(EsLookupFactory.dslKey(node, ctx));
                }
            }
            if (isSearchEndpoint(endpoint, ctx.path())) {
                for (String key : List.of("query", "aggs", "aggregations", "size", "from", "sort", "_source")) {
                    if (!seen.add(key)) continue;
                    EsSchemaModels.DslNode node = schema.findKey(key);
                    if (EsSchemaAvailability.isAvailable(node, ctx.esVersion())) {
                        sink.accept(EsLookupFactory.dslKey(node, ctx));
                    }
                }
            }
            return;
        }
        String parent = ctx.parentProperty();
        EsSchemaModels.DslNode parentNode = schema.findKey(parent);
        Set<String> seen = new LinkedHashSet<>();
        if (parentNode != null) {
            for (String child : parentNode.children()) {
                if (!seen.add(child)) continue;
                EsSchemaModels.DslNode node = schema.findProperty(parent, child);
                if (node == null) {
                    sink.accept(EsLookupFactory.dslKey(new EsSchemaModels.DslNode(
                            child, "property", List.of(parent), List.of(), "object", false, List.of(),
                            null, parent + " property", null, null, false, 90), ctx));
                } else if (EsSchemaAvailability.isAvailable(node, ctx.esVersion())) {
                    sink.accept(EsLookupFactory.dslKey(node, ctx));
                }
            }
            if ("aggs".equals(parent) || "aggregations".equals(parent)) {
                return;
            }
        }
        for (EsSchemaModels.DslNode node : schema.childrenOf(parent)) {
            if (!seen.add(node.key())) continue;
            if (!EsSchemaAvailability.isAvailable(node, ctx.esVersion())) continue;
            sink.accept(EsLookupFactory.dslKey(node, ctx));
        }
    }

    private static void addQueryDsl(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        for (EsSchemaModels.DslNode node : schema.queryDslKeys()) {
            if (!EsSchemaAvailability.isAvailable(node, ctx.esVersion())) continue;
            sink.accept(EsLookupFactory.dslKey(node, ctx));
        }
    }

    private static void addAggregations(Consumer<LookupElement> sink, EsCompletionSchema schema, EsCompletionContext ctx) {
        for (EsSchemaModels.DslNode node : schema.aggregationTypes()) {
            if (!EsSchemaAvailability.isAvailable(node, ctx.esVersion())) continue;
            sink.accept(EsLookupFactory.dslKey(node, ctx));
        }
        EsSchemaModels.DslNode aggs = schema.findKey("aggs");
        if (EsSchemaAvailability.isAvailable(aggs, ctx.esVersion())) {
            sink.accept(EsLookupFactory.dslKey(aggs, ctx));
        }
        EsSchemaModels.DslNode aggregations = schema.findKey("aggregations");
        if (EsSchemaAvailability.isAvailable(aggregations, ctx.esVersion())) {
            sink.accept(EsLookupFactory.dslKey(aggregations, ctx));
        }
    }

    private static void addFields(
            Consumer<LookupElement> sink,
            EsCompletionMetadataSnapshot snapshot,
            EsCompletionContext ctx) {
        String prefix = ctx.prefix() == null ? "" : ctx.prefix().toLowerCase(Locale.ROOT);
        for (EsCompletionMetadataSnapshot.FieldInfo field : snapshot.fields().values()) {
            if (!prefix.isEmpty() && !field.path().toLowerCase(Locale.ROOT).startsWith(prefix)
                    && !field.path().toLowerCase(Locale.ROOT).contains(prefix)) {
                continue;
            }
            sink.accept(EsLookupFactory.field(field, ctx));
        }
    }

    private static EsSchemaModels.Endpoint resolveEndpoint(EsCompletionSchema schema, EsCompletionContext ctx) {
        EsCompletionSchema.ResolvedEndpoint resolved = schema.resolveEndpoint(ctx.method(), ctx.path());
        EsSchemaModels.Endpoint endpoint = resolved == null ? null : resolved.endpoint();
        if (endpoint == null) endpoint = schema.findEndpointByPath(ctx.endpoint());
        if (endpoint == null && ctx.path().contains("_search")) {
            endpoint = schema.findEndpointByPath("_search");
            if (endpoint == null) {
                for (EsSchemaModels.Endpoint candidate : schema.endpoints()) {
                    if ("search".equals(candidate.name())) {
                        return candidate;
                    }
                }
            }
        }
        return endpoint;
    }

    private static boolean isSearchEndpoint(EsSchemaModels.Endpoint endpoint, String path) {
        return endpoint != null
                ? endpoint.name().contains("search")
                : path != null && path.contains("_search");
    }

    private static String endpointKey(EsCompletionContext ctx) {
        if (ctx.endpoint() != null && !ctx.endpoint().isBlank()) return ctx.endpoint();
        if (ctx.path().contains("_search")) return "search";
        return ctx.path();
    }

    private static String leaf(String path) {
        if (path == null || path.isEmpty()) return "";
        int idx = path.lastIndexOf('.');
        String value = idx >= 0 ? path.substring(idx + 1) : path;
        return value.endsWith("[]") ? value.substring(0, value.length() - 2) : value;
    }
}
