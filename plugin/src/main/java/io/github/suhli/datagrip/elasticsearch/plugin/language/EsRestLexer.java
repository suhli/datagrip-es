package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

final class EsRestLexer extends LexerBase {
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
    private static final Set<String> JSON_KEYWORDS = Set.of("true", "false", "null");

    private CharSequence buffer = "";
    private int end;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.end = endOffset;
        this.tokenStart = startOffset;
        locateToken();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return end;
    }

    private void locateToken() {
        if (tokenStart >= end) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }
        char c = buffer.charAt(tokenStart);
        if (Character.isWhitespace(c)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < end && Character.isWhitespace(buffer.charAt(tokenEnd))) tokenEnd++;
            tokenType = TokenType.WHITE_SPACE;
            return;
        }
        if (c == '#' || c == '/' && tokenStart + 1 < end && buffer.charAt(tokenStart + 1) == '/') {
            tokenEnd = lineEnd(tokenStart);
            tokenType = EsRestTokenTypes.COMMENT;
            return;
        }
        if (c == '/' && followsHttpMethod(tokenStart)) {
            tokenEnd = lineEnd(tokenStart);
            for (int i = tokenStart + 1; i + 1 < tokenEnd; i++) {
                if (buffer.charAt(i) == '/' && buffer.charAt(i + 1) == '/'
                        && Character.isWhitespace(buffer.charAt(i - 1))) {
                    tokenEnd = i;
                    break;
                }
            }
            tokenType = EsRestTokenTypes.PATH;
            return;
        }
        if (c == '"') {
            tokenEnd = tokenStart + 1;
            boolean escaped = false;
            while (tokenEnd < end) {
                char current = buffer.charAt(tokenEnd++);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') break;
            }
            tokenType = EsRestTokenTypes.STRING;
            return;
        }
        IElementType punctuation = switch (c) {
            case '{' -> EsRestTokenTypes.LBRACE;
            case '}' -> EsRestTokenTypes.RBRACE;
            case '[' -> EsRestTokenTypes.LBRACKET;
            case ']' -> EsRestTokenTypes.RBRACKET;
            case ':' -> EsRestTokenTypes.COLON;
            case ',' -> EsRestTokenTypes.COMMA;
            default -> null;
        };
        if (punctuation != null) {
            tokenEnd = tokenStart + 1;
            tokenType = punctuation;
            return;
        }
        if (c == '-' || Character.isDigit(c)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < end && "0123456789.eE+-".indexOf(buffer.charAt(tokenEnd)) >= 0) tokenEnd++;
            tokenType = EsRestTokenTypes.NUMBER;
            return;
        }

        tokenEnd = tokenStart + 1;
        while (tokenEnd < end && !Character.isWhitespace(buffer.charAt(tokenEnd))
                && "{}[],:\"#".indexOf(buffer.charAt(tokenEnd)) < 0) {
            tokenEnd++;
        }
        String word = buffer.subSequence(tokenStart, tokenEnd).toString();
        String upper = word.toUpperCase(Locale.ROOT);
        if (METHODS.contains(upper) && isFirstTokenOnLine(tokenStart)) {
            tokenType = EsRestTokenTypes.METHOD;
        } else if (JSON_KEYWORDS.contains(word)) {
            tokenType = EsRestTokenTypes.KEYWORD;
        } else {
            tokenType = EsRestTokenTypes.IDENTIFIER;
        }
    }

    private boolean followsHttpMethod(int offset) {
        int lineStart = offset;
        while (lineStart > 0 && buffer.charAt(lineStart - 1) != '\n'
                && buffer.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        String prefix = buffer.subSequence(lineStart, offset).toString().trim();
        return METHODS.contains(prefix.toUpperCase(Locale.ROOT));
    }

    private boolean isFirstTokenOnLine(int offset) {
        for (int i = offset - 1; i >= 0 && buffer.charAt(i) != '\n' && buffer.charAt(i) != '\r'; i--) {
            if (!Character.isWhitespace(buffer.charAt(i))) return false;
        }
        return true;
    }

    private int lineEnd(int offset) {
        int result = offset;
        while (result < end && buffer.charAt(result) != '\n' && buffer.charAt(result) != '\r') result++;
        return result;
    }
}
