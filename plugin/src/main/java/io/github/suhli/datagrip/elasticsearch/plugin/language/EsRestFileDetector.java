package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.intellij.database.Dbms;
import com.intellij.database.console.DbConsoleRootType;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.sql.dialects.SqlLanguageDialect;
import com.intellij.sql.psi.SqlFile;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/** Detects Elasticsearch REST console / query files in DataGrip. */
public final class EsRestFileDetector {
    private static final Pattern REQUEST_LINE = Pattern.compile(
            "(?m)^\\s*(GET|POST|PUT|DELETE|PATCH|HEAD)\\s+\\S");

    private EsRestFileDetector() {}

    public static boolean isEsRestFile(@Nullable PsiFile file) {
        if (file == null) return false;
        if (file.getLanguage().isKindOf(EsRestLanguage.INSTANCE)) return true;
        if (file instanceof SqlFile sqlFile) {
            SqlLanguageDialect dialect = sqlFile.getSqlLanguage();
            if (dialect instanceof EsRestLanguage) return true;
            if (dialect != null && dialect.isKindOf(EsRestLanguage.INSTANCE)) return true;
            if (dialect != null && dialect.getDbms() == Dbms.byName("ELASTICSEARCH")) return true;
        }
        Project project = file.getProject();
        VirtualFile virtualFile = file.getVirtualFile();
        if (project != null && virtualFile != null) {
            Language substituted = DbConsoleRootType.getInstance().substituteLanguage(project, virtualFile);
            if (substituted != null && substituted.isKindOf(EsRestLanguage.INSTANCE)) return true;
        }
        return looksLikeEsRestConsole(file.getText());
    }

    public static boolean isEsRestDocument(@Nullable Project project, @Nullable Document document) {
        if (project == null || document == null) return false;
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (isEsRestFile(file)) return true;
        VirtualFile virtualFile = FileDocumentManager.getInstance().getFile(document);
        if (virtualFile != null) {
            Language substituted = DbConsoleRootType.getInstance().substituteLanguage(project, virtualFile);
            if (substituted != null && substituted.isKindOf(EsRestLanguage.INSTANCE)) return true;
        }
        return looksLikeEsRestConsole(document.getCharsSequence());
    }

    public static boolean looksLikeEsRestConsole(CharSequence text) {
        if (text == null || text.isEmpty()) return false;
        return REQUEST_LINE.matcher(text).find();
    }
}
