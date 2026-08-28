package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

public final class EsRestFileType extends LanguageFileType {
    public static final EsRestFileType INSTANCE = new EsRestFileType();

    private EsRestFileType() {
        super(EsRestLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "Elasticsearch REST";
    }

    @Override
    public @NotNull String getDescription() {
        return "Elasticsearch REST Console request";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "esrest";
    }

    @Override
    public @Nullable Icon getIcon() {
        return null;
    }
}
