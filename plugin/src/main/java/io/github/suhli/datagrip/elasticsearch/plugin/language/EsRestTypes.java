package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.sql.psi.stubs.SqlFileElementType;

final class EsRestTypes {
    static final IFileElementType FILE =
            new SqlFileElementType("ES_REST_FILE", EsRestLanguage.INSTANCE);
    static final IElementType REQUEST = type("REQUEST");
    static final IElementType OBJECT = type("OBJECT");
    static final IElementType ARRAY = type("ARRAY");
    static final IElementType METHOD = type("METHOD");
    static final IElementType PATH = type("PATH");
    static final IElementType COMMENT = type("COMMENT");
    static final IElementType STRING = type("STRING");
    static final IElementType NUMBER = type("NUMBER");
    static final IElementType KEYWORD = type("KEYWORD");
    static final IElementType LBRACE = type("LBRACE");
    static final IElementType RBRACE = type("RBRACE");
    static final IElementType LBRACKET = type("LBRACKET");
    static final IElementType RBRACKET = type("RBRACKET");
    static final IElementType COLON = type("COLON");
    static final IElementType COMMA = type("COMMA");
    static final IElementType IDENTIFIER = type("IDENTIFIER");

    static final TokenSet COMMENTS = TokenSet.create(COMMENT);
    static final TokenSet STRINGS = TokenSet.create(STRING);

    private EsRestTypes() {}

    private static IElementType type(String name) {
        return new IElementType(name, EsRestLanguage.INSTANCE);
    }
}
