package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.lang.Language;

public final class EsRestLanguage extends Language {
    public static final EsRestLanguage INSTANCE = new EsRestLanguage();

    private EsRestLanguage() {
        super("ElasticsearchREST");
    }
}
