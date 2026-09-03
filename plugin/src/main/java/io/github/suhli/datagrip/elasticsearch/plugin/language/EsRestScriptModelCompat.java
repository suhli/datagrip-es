package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.database.script.ScriptModelUtilCore;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.SyntaxTraverser;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
            Condition<V> result = invokeWholeFileCondition(
                    WHOLE_FILE_CONDITION_6, traverser, range, wsOrComment, statementSeparator, statement, batchBlock);
            if (result != null) return result;
        }
        if (WHOLE_FILE_CONDITION_5 != null) {
            Condition<V> result = invokeWholeFileCondition(
                    WHOLE_FILE_CONDITION_5, traverser, range, wsOrComment, statementSeparator, statement);
            if (result != null) return result;
        }
        return node -> false;
    }

    private static Method findWholeFileConditionMethod(int parameterCount) {
        for (Method method : ScriptModelUtilCore.class.getMethods()) {
            if (!"wholeFileCondition".equals(method.getName())) continue;
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != parameterCount) continue;
            Class<?>[] types = method.getParameterTypes();
            if (!types[0].isAssignableFrom(SyntaxTraverser.class)
                    || !types[1].isAssignableFrom(TextRange.class)) continue;
            boolean compatible = true;
            for (int i = 2; i < types.length; i++) {
                if (!types[i].isAssignableFrom(Condition.class)) {
                    compatible = false;
                    break;
                }
            }
            if (compatible && Condition.class.isAssignableFrom(method.getReturnType())) return method;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <V> Condition<V> invokeWholeFileCondition(
            Method method, Object... args) {
        try {
            return (Condition<V>) method.invoke(null, args);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;
        }
    }
}
