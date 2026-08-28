package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.formatting.FormattingModelProvider;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.Wrap;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class EsRestFormattingModelBuilder implements FormattingModelBuilder {
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext context) {
        PsiElement element = context.getPsiElement();
        CodeStyleSettings settings = context.getCodeStyleSettings();
        return FormattingModelProvider.createFormattingModelForPsiFile(
                element.getContainingFile(),
                new EsRestBlock(element.getNode(), null),
                settings);
    }

    private static final class EsRestBlock extends AbstractBlock {
        private final Indent indent;

        EsRestBlock(ASTNode node, Indent indent) {
            super(node, (Wrap) null, (Alignment) null);
            this.indent = indent;
        }

        @Override
        protected List<Block> buildChildren() {
            List<Block> children = new ArrayList<>();
            boolean container = isContainer(myNode.getElementType());
            for (ASTNode child = myNode.getFirstChildNode(); child != null;
                    child = child.getTreeNext()) {
                if (child.getElementType() == TokenType.WHITE_SPACE) continue;
                IElementType type = child.getElementType();
                Indent childIndent = container && !isBracket(type)
                        ? Indent.getNormalIndent()
                        : Indent.getNoneIndent();
                children.add(new EsRestBlock(child, childIndent));
            }
            return children;
        }

        @Override
        public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
            if (!(child2 instanceof EsRestBlock right)) return null;
            EsRestBlock left = child1 instanceof EsRestBlock block ? block : null;
            IElementType rightType = right.myNode.getElementType();
            IElementType leftType = left == null ? null : left.myNode.getElementType();

            if (rightType == EsRestTypes.METHOD) {
                return spacing(0, left == null ? 0 : 2);
            }
            if (leftType == EsRestTypes.METHOD && rightType == EsRestTypes.PATH) {
                return spacing(1, 0);
            }
            if (leftType == EsRestTypes.PATH && rightType == EsRestTypes.COMMENT) {
                return spacing(1, 0);
            }
            if (leftType == EsRestTypes.PATH || leftType == EsRestTypes.COMMENT) {
                return spacing(0, 1);
            }
            if ((leftType == EsRestTypes.LBRACE && rightType == EsRestTypes.RBRACE)
                    || (leftType == EsRestTypes.LBRACKET
                    && rightType == EsRestTypes.RBRACKET)) {
                return spacing(0, 0);
            }
            if (leftType == EsRestTypes.LBRACE || leftType == EsRestTypes.LBRACKET
                    || leftType == EsRestTypes.COMMA || isClosing(rightType)) {
                return spacing(0, 1);
            }
            if (rightType == EsRestTypes.COLON || rightType == EsRestTypes.COMMA) {
                return spacing(0, 0);
            }
            if (leftType == EsRestTypes.COLON) return spacing(1, 0);
            return spacing(0, 0);
        }

        @Override
        public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
            return new ChildAttributes(
                    isContainer(myNode.getElementType())
                            ? Indent.getNormalIndent()
                            : Indent.getNoneIndent(),
                    null);
        }

        @Override
        public @Nullable Indent getIndent() {
            return indent;
        }

        @Override
        public boolean isLeaf() {
            return myNode.getFirstChildNode() == null;
        }

        private static Spacing spacing(int spaces, int lineFeeds) {
            return Spacing.createSpacing(spaces, spaces, lineFeeds, true, 2);
        }

        private static boolean isContainer(IElementType type) {
            return type == EsRestTypes.OBJECT || type == EsRestTypes.ARRAY;
        }

        private static boolean isBracket(IElementType type) {
            return type == EsRestTypes.LBRACE || type == EsRestTypes.RBRACE
                    || type == EsRestTypes.LBRACKET || type == EsRestTypes.RBRACKET;
        }

        private static boolean isClosing(IElementType type) {
            return type == EsRestTypes.RBRACE || type == EsRestTypes.RBRACKET;
        }
    }
}
