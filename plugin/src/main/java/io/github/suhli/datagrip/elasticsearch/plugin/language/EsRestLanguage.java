package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.database.Dbms;
import com.intellij.sql.dialects.base.SqlLanguageDialectBase;
import com.intellij.sql.dialects.base.TokensHelper;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

import java.util.Set;

public final class EsRestLanguage extends SqlLanguageDialectBase {
    public static final EsRestLanguage INSTANCE = new EsRestLanguage();

    private EsRestLanguage() {
        super("ElasticsearchREST");
    }

    @Override
    protected TokensHelper createTokensHelper() {
        return createTokensHelper(EsRestTypes.class);
    }

    @Override
    public Dbms getDbms() {
        return Dbms.byName("ELASTICSEARCH");
    }

    @Override
    public boolean isOperatorSupported(IElementType type) {
        return false;
    }

    @Override
    public Set<String> getSystemVariables() {
        return Set.of();
    }

    @Override
    public TokenSet getStatementSeparators() {
        return TokenSet.EMPTY;
    }
}
