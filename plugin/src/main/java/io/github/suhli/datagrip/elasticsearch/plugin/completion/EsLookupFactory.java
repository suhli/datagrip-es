package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata.EsCompletionMetadataSnapshot;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCompletionContext;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;

public final class EsLookupFactory {
    private EsLookupFactory() {}

    public static LookupElement endpoint(EsSchemaModels.Endpoint endpoint, EsCompletionContext context) {
        String primary = EsCompletionSchemaPaths.primaryPath(endpoint);
        String methods = String.join(", ", endpoint.methods());
        LookupElementBuilder builder = LookupElementBuilder.create(primary)
                .withPresentableText(primary)
                .withTypeText(methods, true)
                .withInsertHandler(new EsSnippetInsertHandler(primary, false, false));
        double priority = endpoint.deprecated() ? 50 : 100;
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

    public static LookupElement queryParam(EsSchemaModels.QueryParam param, boolean alreadyQuoted) {
        double priority = param.deprecated() ? 40 : 90;
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(param.name())
                        .withTypeText("query parameter", true)
                        .withStrikeoutness(param.deprecated())
                        .withInsertHandler(new EsSnippetInsertHandler(param.name(), alreadyQuoted, false)),
                priority);
    }

    public static LookupElement enumValue(String value, String typeText, boolean alreadyQuoted) {
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(value)
                        .withTypeText(typeText, true)
                        .withInsertHandler(new EsSnippetInsertHandler(value, alreadyQuoted, false)),
                100);
    }

    public static LookupElement dslKey(EsSchemaModels.DslNode node, EsCompletionContext context) {
        String snippet = node.snippet();
        LookupElementBuilder builder = LookupElementBuilder.create(node.key())
                .withPresentableText(node.key())
                .withTypeText(node.description() == null || node.description().isBlank()
                        ? node.category() : node.description(), true)
                .withStrikeoutness(node.deprecated());
        if (snippet != null && !snippet.isBlank() && context.location() ==
                io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCaretLocation.BODY) {
            builder = builder.withInsertHandler(new EsSnippetInsertHandler(
                    node.key(), context.insideString(), true, adjustSnippet(snippet, context)));
        } else {
            builder = builder.withInsertHandler(new EsSnippetInsertHandler(
                    node.key(), context.insideString(), true));
        }
        double priority = node.priority();
        if (node.deprecated()) priority -= 30;
        return PrioritizedLookupElement.withPriority(builder, priority);
    }

    public static LookupElement field(EsCompletionMetadataSnapshot.FieldInfo field, EsCompletionContext context) {
        String type = field.primaryType();
        String typeText = type + (field.multiField() ? " · multi-field" : "");
        return PrioritizedLookupElement.withPriority(
                LookupElementBuilder.create(field.path())
                        .withTypeText(typeText, true)
                        .withInsertHandler(new EsSnippetInsertHandler(
                                field.path(), context.insideString(),
                                context.expectedKind() ==
                                        io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind.FIELD_KEY)),
                120);
    }

    private static String adjustSnippet(String snippet, EsCompletionContext context) {
        if (context.insideString()) {
            // When caret is inside quotes, drop the leading quoted key quotes.
            if (snippet.startsWith("\"")) {
                int second = snippet.indexOf('"', 1);
                if (second > 1 && second + 1 < snippet.length() && snippet.charAt(second + 1) == ':') {
                    return snippet.substring(1, second) + snippet.substring(second + 1);
                }
            }
        }
        return snippet;
    }
}
