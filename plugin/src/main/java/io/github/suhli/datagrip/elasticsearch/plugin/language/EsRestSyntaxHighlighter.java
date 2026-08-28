package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

final class EsRestSyntaxHighlighter extends SyntaxHighlighterBase {
    private static final TextAttributesKey METHOD = TextAttributesKey.createTextAttributesKey(
            "ES_REST_METHOD", DefaultLanguageHighlighterColors.KEYWORD);
    private static final TextAttributesKey PATH = TextAttributesKey.createTextAttributesKey(
            "ES_REST_PATH", DefaultLanguageHighlighterColors.METADATA);
    private static final TextAttributesKey COMMENT = TextAttributesKey.createTextAttributesKey(
            "ES_REST_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    private static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
            "ES_REST_STRING", DefaultLanguageHighlighterColors.STRING);
    private static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
            "ES_REST_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    private static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
            "ES_REST_JSON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    private static final TextAttributesKey PUNCTUATION = TextAttributesKey.createTextAttributesKey(
            "ES_REST_PUNCTUATION", DefaultLanguageHighlighterColors.BRACES);
    private static final TextAttributesKey BAD = TextAttributesKey.createTextAttributesKey(
            "ES_REST_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);
    private static final TextAttributesKey[] EMPTY = TextAttributesKey.EMPTY_ARRAY;

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new EsRestLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == EsRestTypes.METHOD) return pack(METHOD);
        if (tokenType == EsRestTypes.PATH) return pack(PATH);
        if (tokenType == EsRestTypes.COMMENT) return pack(COMMENT);
        if (tokenType == EsRestTypes.STRING) return pack(STRING);
        if (tokenType == EsRestTypes.NUMBER) return pack(NUMBER);
        if (tokenType == EsRestTypes.KEYWORD) return pack(KEYWORD);
        if (tokenType == EsRestTypes.LBRACE || tokenType == EsRestTypes.RBRACE
                || tokenType == EsRestTypes.LBRACKET || tokenType == EsRestTypes.RBRACKET
                || tokenType == EsRestTypes.COLON || tokenType == EsRestTypes.COMMA) {
            return pack(PUNCTUATION);
        }
        if (tokenType == TokenType.BAD_CHARACTER) return pack(BAD);
        return EMPTY;
    }
}
