package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Brace pairs for JSON object/array delimiters in Elasticsearch REST consoles. */
public final class EsRestBraceMatcher implements PairedBraceMatcher {
    public static final EsRestBraceMatcher INSTANCE = new EsRestBraceMatcher();

    private static final BracePair[] PAIRS = {
            new BracePair(EsRestTypes.LBRACE, EsRestTypes.RBRACE, true),
            new BracePair(EsRestTypes.LBRACKET, EsRestTypes.RBRACKET, true),
    };

    private EsRestBraceMatcher() {}

    @Override
    public @NotNull BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(
            @NotNull IElementType lbraceType, @Nullable IElementType contextType) {
        return contextType != EsRestTypes.STRING;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        return openingBraceOffset;
    }
}
