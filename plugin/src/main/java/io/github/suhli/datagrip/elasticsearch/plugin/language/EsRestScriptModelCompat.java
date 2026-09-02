package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.database.script.ScriptModelUtilCore;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.SyntaxTraverser;

import java.lang.reflect.Method;

/** Cross-version bridge for Database Tools script helpers. */
final class EsRestScriptModelCompat {
    private static final Method WHOLE_FILE_CONDITION_6 = findWholeFileConditionMethod(6);
    private static final Method WHOLE_FILE_CONDITION_5 = findWholeFileConditionMethod(5);

    private EsRestScriptModelCompat() {}

    static <V> Condition<V> wholeFileCondition(
            SyntaxTraverser<V> traverser,
            TextRange range,
            Condition<? super V> wsOrComment,
            Condition<? super V> statementSeparator,
            Condition<? super V> statement,
            Condition<? super V> batchBlock) {
        if (WHOLE_FILE_CONDITION_6 != null) {
            return invokeWholeFileCondition(
                    WHOLE_FILE_CONDITION_6, traverser, range, wsOrComment, statementSeparator, statement, batchBlock);
        }
        if (WHOLE_FILE_CONDITION_5 != null) {
            return invokeWholeFileCondition(
                    WHOLE_FILE_CONDITION_5, traverser, range, wsOrComment, statementSeparator, statement);
        }
        return node -> false;
    }

    private static Method findWholeFileConditionMethod(int parameterCount) {
        for (Method method : ScriptModelUtilCore.class.getMethods()) {
            if (!"wholeFileCondition".equals(method.getName())) continue;
            if (method.getParameterCount() == parameterCount) return method;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <V> Condition<V> invokeWholeFileCondition(
            Method method, Object... args) {
        try {
            return (Condition<V>) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            return node -> false;
        }
    }
}
