package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
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
import com.intellij.sql.psi.SqlElement;
import com.intellij.sql.psi.SqlStatement;
import com.intellij.sql.psi.SqlVisitor;
import com.intellij.sql.psi.impl.SqlFileImpl;
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
            parseBodyUntilNextRequest(builder);
        } else if (builder.getTokenType() != EsRestTypes.METHOD && !builder.eof()) {
            parseBodyUntilNextRequest(builder);
        }
        request.done(EsRestTypes.REQUEST);
    }

    private static void parseBodyUntilNextRequest(PsiBuilder builder) {
        while (!builder.eof() && builder.getTokenType() != EsRestTypes.METHOD) {
            IElementType token = builder.getTokenType();
            if (token == EsRestTypes.LBRACE || token == EsRestTypes.LBRACKET) {
                parseToken(builder);
            } else if (token == EsRestTypes.COMMENT || token == TokenType.WHITE_SPACE) {
                builder.advanceLexer();
            } else {
                builder.advanceLexer();
            }
        }
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
        if (node.getElementType() == EsRestTypes.REQUEST) {
            return new RequestPsi(node);
        }
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        // SqlFileImpl is the standard integration point for SQL-dialect file types in
        // Database Tools. It lives under *.impl but is required for statement execution.
        return new SqlFileImpl(viewProvider, EsRestLanguage.INSTANCE);
    }

    private static final class RequestPsi extends ASTWrapperPsiElement implements SqlStatement {
        RequestPsi(ASTNode node) {
            super(node);
        }

        @Override
        public void accept(@NotNull SqlVisitor visitor) {
            visitor.visitSqlStatement(this);
        }

        @Override
        public void acceptChildren(@NotNull SqlVisitor visitor) {
            for (PsiElement child = getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof SqlElement sqlElement) {
                    sqlElement.accept(visitor);
                }
            }
        }
    }
}
