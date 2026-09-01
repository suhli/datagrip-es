package io.github.suhli.datagrip.elasticsearch.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;

/** CLI entry point: schema.json -> completion resources. */
public final class CompletionGeneratorMain {
    private CompletionGeneratorMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: CompletionGeneratorMain <schema.json> <outputDir> [specVersion]");
            System.exit(2);
        }
        Path schema = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        String specVersion = args.length > 2 ? args[2] : "unknown";
        Files.createDirectories(outputDir);

        CompletionMetadata metadata = new CompletionGenerator().generate(schema, specVersion);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputDir.resolve("es-api-completion.json").toFile(), metadata.api());
        mapper.writeValue(outputDir.resolve("es-dsl-completion.json").toFile(), metadata.dsl());
        System.out.println("Generated completion resources into " + outputDir.toAbsolutePath());
        System.out.println("endpoints=" + metadata.api().endpoints().size()
                + " keys=" + metadata.dsl().keys().size());
    }
}
