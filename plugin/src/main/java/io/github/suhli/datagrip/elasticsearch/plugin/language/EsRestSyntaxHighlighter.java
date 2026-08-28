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
        if (tokenType == EsRestTokenTypes.METHOD) return pack(METHOD);
        if (tokenType == EsRestTokenTypes.PATH) return pack(PATH);
        if (tokenType == EsRestTokenTypes.COMMENT) return pack(COMMENT);
        if (tokenType == EsRestTokenTypes.STRING) return pack(STRING);
        if (tokenType == EsRestTokenTypes.NUMBER) return pack(NUMBER);
        if (tokenType == EsRestTokenTypes.KEYWORD) return pack(KEYWORD);
        if (tokenType == EsRestTokenTypes.LBRACE || tokenType == EsRestTokenTypes.RBRACE
                || tokenType == EsRestTokenTypes.LBRACKET || tokenType == EsRestTokenTypes.RBRACKET
                || tokenType == EsRestTokenTypes.COLON || tokenType == EsRestTokenTypes.COMMA) {
            return pack(PUNCTUATION);
        }
        if (tokenType == TokenType.BAD_CHARACTER) return pack(BAD);
        return EMPTY;
    }
}
