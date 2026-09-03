package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCaretLocation;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCompletionContext;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaAvailability;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;

public final class EsLookupFactory {
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"};

    private EsLookupFactory() {}

    public static LookupElement httpMethod(String method) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(method)
                        .withPresentableText(method)
                        .withTypeText("HTTP method", true)
                        .withInsertHandler(new EsSnippetInsertHandler(method + " ", false, false)),
                120);
    }

    public static LookupElement endpoint(EsSchemaModels.Endpoint endpoint, EsCompletionContext context) {
        String primary = EsCompletionSchemaPaths.primaryPath(endpoint);
        String methods = String.join(", ", endpoint.methods());
        LookupElementBuilder builder = LookupElementBuilder.create(primary)
                .withPresentableText(primary)
                .withTypeText(methods, true)
                .withInsertHandler(new EsSnippetInsertHandler(primary, false, false));
        boolean deprecated = EsSchemaAvailability.status(endpoint, context.esVersion())
                == EsSchemaAvailability.Status.DEPRECATED;
        builder = builder.withStrikeoutness(deprecated);
        double priority = deprecated ? 50 : 100;
        if (!context.method().isEmpty() && !endpoint.methods().contains(context.method())) {
            priority -= 40;
        }
        return PrioritizedLookupElement.withPriority(builder, priority);
    }

    public static LookupElement index(EsCompletionMetadataSnapshot.IndexObject index) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(index.name())
                        .withTypeText(index.kind(), true)
                        .withInsertHandler(new EsSnippetInsertHandler(index.name(), false, false)),
                "index".equals(index.kind()) ? 100 : 95);
    }

    public static LookupElement queryParam(
            EsSchemaModels.QueryParam param, boolean alreadyQuoted, String clusterVersion) {
        boolean deprecated = EsSchemaAvailability.status(param, clusterVersion)
                == EsSchemaAvailability.Status.DEPRECATED;
        double priority = deprecated ? 40 : 90;
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(param.name())
                        .withTypeText("query parameter", true)
                        .withStrikeoutness(deprecated)
                        .withInsertHandler(new EsSnippetInsertHandler(param.name(), alreadyQuoted, false)),
                priority);
    }

    public static LookupElement enumValue(String value, String typeText, boolean alreadyQuoted) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(value)
                        .withTypeText(typeText, true)
                        .withInsertHandler(new EsSnippetInsertHandler(
                                EsSnippetInsertHandler.formatJsonStringValue(value, alreadyQuoted),
                                false, false)),
                100);
    }

    public static LookupElement dslKey(EsSchemaModels.DslNode node, EsCompletionContext context) {
        String snippet = resolveSnippet(node, context);
        LookupElementBuilder builder = LookupElementBuilder.create(node.key())
                .withPresentableText(node.key())
                .withTypeText(node.description() == null || node.description().isBlank()
                        ? node.category() : node.description(), true)
                .withStrikeoutness(EsSchemaAvailability.status(node, context.esVersion())
                        == EsSchemaAvailability.Status.DEPRECATED);
        if (snippet != null && !snippet.isBlank()) {
            builder = builder.withInsertHandler(new EsSnippetInsertHandler(
                    node.key(), context.insideString(), true, snippet));
        } else {
            builder = builder.withInsertHandler(new EsSnippetInsertHandler(
                    node.key(), context.insideString(), true));
        }
        double priority = node.priority();
        if (EsSchemaAvailability.status(node, context.esVersion())
                == EsSchemaAvailability.Status.DEPRECATED) priority -= 30;
        return PrioritizedLookupElement.withPriority(builder, priority);
    }

    public static LookupElement field(
            EsCompletionMetadataSnapshot.FieldInfo field,
            EsCompletionContext context) {
        return field(field, context, null);
    }

    public static LookupElement field(
            EsCompletionMetadataSnapshot.FieldInfo field,
            EsCompletionContext context,
            String partialDescription) {
        String type = field.primaryType();
        String typeText = type + (field.multiField() ? " · multi-field" : "");
        boolean asKey = context.expectedKind() == EsExpectedKind.FIELD_KEY;
        LookupElementBuilder builder = LookupElementBuilder.create(field.path())
                .withTypeText(typeText, true);
        if (partialDescription != null && !partialDescription.isBlank()) {
            builder = builder.withTailText(" · " + partialDescription, true);
        }
        if (asKey) {
            if (EsCompletionContextResolver.isEmptyFieldKeyPlaceholder(context)) {
                builder = builder.withInsertHandler(EsSnippetInsertHandler.forEmptyFieldKeyPair(field.path()));
            } else if (context.insideString()) {
                builder = builder.withInsertHandler(EsSnippetInsertHandler.forFieldKeyInsideString(field.path()));
            } else {
                builder = builder.withInsertHandler(EsSnippetInsertHandler.forFieldKeyPair(field.path()));
            }
        } else {
            String insertion = EsSnippetInsertHandler.formatJsonStringValue(
                    field.path(), context.insideString());
            builder = builder.withInsertHandler(
                    new EsSnippetInsertHandler(insertion, context.insideString(), false));
        }
        return PrioritizedLookupElement.withPriority(builder, 120);
    }

    private static String resolveSnippet(EsSchemaModels.DslNode node, EsCompletionContext context) {
        if (node.snippet() != null && !node.snippet().isBlank()) {
            return adjustSnippet(node.snippet(), context);
        }
        if (context.location() == EsCaretLocation.BODY
                && (context.expectedKind() == EsExpectedKind.BODY_KEY
                || context.expectedKind() == EsExpectedKind.QUERY_DSL
                || context.expectedKind() == EsExpectedKind.AGGREGATION_TYPE)) {
            String generated = EsSnippetDefaults.bodyKeySnippet(node.key(), node.valueType());
            return context.insideString()
                    ? EsSnippetDefaults.adjustForInsideString(generated)
                    : generated;
        }
        return null;
    }

    private static String adjustSnippet(String snippet, EsCompletionContext context) {
        if (context.insideString()) {
            return EsSnippetDefaults.adjustForInsideString(snippet);
        }
        return snippet;
    }
}
