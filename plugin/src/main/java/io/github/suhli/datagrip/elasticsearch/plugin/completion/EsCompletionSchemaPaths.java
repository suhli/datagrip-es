package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;

final class EsCompletionSchemaPaths {
    private EsCompletionSchemaPaths() {}

    static String primaryPath(EsSchemaModels.Endpoint endpoint) {
        for (String path : endpoint.paths()) {
            String actionable = EsCompletionSchema.actionableEndpoint(path);
            if (!actionable.isBlank()) return actionable.startsWith("/") ? actionable : actionable;
        }
        return endpoint.name();
    }
}
