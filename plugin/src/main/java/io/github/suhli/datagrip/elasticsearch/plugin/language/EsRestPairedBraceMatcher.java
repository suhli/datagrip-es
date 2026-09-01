package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter;

/** Database-console integration entry point for matched-brace highlighting. */
public final class EsRestPairedBraceMatcher extends PairedBraceMatcherAdapter {
    public EsRestPairedBraceMatcher() {
        super(EsRestBraceMatcher.INSTANCE, EsRestLanguage.INSTANCE);
    }
}
