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
                if (builder.getTokenType() == EsRestTypes.METHOD) {
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
            if (token == TokenType.WHITE_SPACE || token == EsRestTypes.COMMENT) {
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
            if (token == EsRestTypes.PATH
                    || token == EsRestTypes.COMMENT
                    || token == TokenType.WHITE_SPACE) {
                builder.advanceLexer();
                continue;
            }
            break;
        }
        if (builder.getTokenType() == EsRestTypes.LBRACE
                || builder.getTokenType() == EsRestTypes.LBRACKET) {
            parseToken(builder);
        } else if (builder.getTokenType() != EsRestTypes.METHOD && !builder.eof()) {
            while (!builder.eof() && builder.getTokenType() != EsRestTypes.METHOD) {
                parseToken(builder);
            }
        }
        request.done(EsRestTypes.REQUEST);
    }

    private static void parseToken(PsiBuilder builder) {
        IElementType token = builder.getTokenType();
        if (token == EsRestTypes.LBRACE) {
            parseComposite(builder, EsRestTypes.RBRACE, EsRestTypes.OBJECT);
        } else if (token == EsRestTypes.LBRACKET) {
            parseComposite(builder, EsRestTypes.RBRACKET, EsRestTypes.ARRAY);
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
        return EsRestTypes.FILE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return EsRestTypes.COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return EsRestTypes.STRINGS;
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
