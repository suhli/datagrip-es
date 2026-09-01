package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the Elasticsearch REST reformat handler once per IDE session. */
public final class EsRestEditorActionRegistrar implements StartupActivity.DumbAware {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    @Override
    public void runActivity(@NotNull Project project) {
        if (!REGISTERED.compareAndSet(false, true)) return;
        EditorActionManager manager = EditorActionManager.getInstance();
        EditorActionHandler current = manager.getActionHandler(IdeActions.ACTION_EDITOR_REFORMAT);
        if (current instanceof EsRestReformatCodeHandler) return;
        manager.setActionHandler(IdeActions.ACTION_EDITOR_REFORMAT, new EsRestReformatCodeHandler(current));
    }
}
