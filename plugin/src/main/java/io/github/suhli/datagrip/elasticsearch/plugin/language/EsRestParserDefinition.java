package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class EsRestParserDefinition implements ParserDefinition {
    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new EsRestLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            PsiBuilder.Marker file = builder.mark();
            while (!builder.eof()) {
                skipTopLevel(builder);
                if (builder.eof()) break;
                if (builder.getTokenType() == EsRestTokenTypes.METHOD) {
                    parseRequest(builder);
                } else {
                    builder.advanceLexer();
                }
            }
            file.done(root);
            return builder.getTreeBuilt();
        };
    }

    private static void skipTopLevel(PsiBuilder builder) {
        while (true) {
            IElementType token = builder.getTokenType();
            if (token == TokenType.WHITE_SPACE || token == EsRestTokenTypes.COMMENT) {
                builder.advanceLexer();
                continue;
            }
            break;
        }
    }

    private static void parseRequest(PsiBuilder builder) {
        PsiBuilder.Marker request = builder.mark();
        builder.advanceLexer(); // METHOD
        while (true) {
            IElementType token = builder.getTokenType();
            if (token == EsRestTokenTypes.PATH
                    || token == EsRestTokenTypes.COMMENT
                    || token == TokenType.WHITE_SPACE) {
                builder.advanceLexer();
                continue;
            }
            break;
        }
        if (builder.getTokenType() == EsRestTokenTypes.LBRACE
                || builder.getTokenType() == EsRestTokenTypes.LBRACKET) {
            parseToken(builder);
        } else if (builder.getTokenType() != EsRestTokenTypes.METHOD && !builder.eof()) {
            while (!builder.eof() && builder.getTokenType() != EsRestTokenTypes.METHOD) {
                parseToken(builder);
            }
        }
        request.done(EsRestTokenTypes.REQUEST);
    }

    private static void parseToken(PsiBuilder builder) {
        IElementType token = builder.getTokenType();
        if (token == EsRestTokenTypes.LBRACE) {
            parseComposite(builder, EsRestTokenTypes.RBRACE, EsRestTokenTypes.OBJECT);
        } else if (token == EsRestTokenTypes.LBRACKET) {
            parseComposite(builder, EsRestTokenTypes.RBRACKET, EsRestTokenTypes.ARRAY);
        } else {
            builder.advanceLexer();
        }
    }

    private static void parseComposite(
            PsiBuilder builder, IElementType closing, IElementType composite) {
        PsiBuilder.Marker marker = builder.mark();
        builder.advanceLexer();
        while (!builder.eof() && builder.getTokenType() != closing) parseToken(builder);
        if (builder.getTokenType() == closing) builder.advanceLexer();
        marker.done(composite);
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return EsRestTokenTypes.FILE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return EsRestTokenTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return EsRestTokenTypes.STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new PsiFileBase(viewProvider, EsRestLanguage.INSTANCE) {
            @Override
            public @NotNull com.intellij.openapi.fileTypes.FileType getFileType() {
                return EsRestFileType.INSTANCE;
            }

            @Override
            public String toString() {
                return "Elasticsearch REST Console";
            }
        };
    }
}
