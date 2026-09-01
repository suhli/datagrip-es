package io.github.suhli.datagrip.elasticsearch.plugin;

import com.intellij.database.dataSource.DatabaseDriver;
import com.intellij.database.dataSource.DatabaseDriverManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.classpath.SimpleClasspathElement;
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the JDBC JAR shipped in this plugin directly to DataGrip's driver
 * classpath. A bundled local driver must not depend on DataGrip's download
 * workflow.
 *
 * <p>Uses {@link SimpleClasspathElement} and {@link DatabaseDriverManager} from
 * Database Tools; these are not fully public APIs but are the same integration
 * surface JetBrains uses for bundled JDBC drivers.
 */
public final class BundledDriverStartupActivity implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(BundledDriverStartupActivity.class);
    private static final String DRIVER_ID = "elasticsearch-rest";
    private static final String DRIVER_JAR = "lib/elasticsearch-rest-jdbc.jar";

    @Override
    public void runActivity(@NotNull Project project) {
        Path driverJar = locateBundledDriverJar();
        if (driverJar == null || !Files.isRegularFile(driverJar)) {
            LOG.warn("Bundled Elasticsearch REST JDBC driver is missing");
            return;
        }

        DatabaseDriverManager manager = DatabaseDriverManager.getInstance();
        DatabaseDriver driver = manager.getDriver(DRIVER_ID);
        if (driver == null) {
            LOG.warn("Elasticsearch REST DataGrip driver definition is unavailable");
            return;
        }

        VirtualFile localJar = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(driverJar);
        VirtualFile jarRoot = localJar == null
                ? null
                : JarFileSystem.getInstance().getJarRootForLocalFile(localJar);
        if (jarRoot == null) {
            LOG.warn("Cannot resolve bundled Elasticsearch REST JDBC driver: " + driverJar);
            return;
        }

        String driverUrl = jarRoot.getUrl();
        List<SimpleClasspathElement> elements =
                new ArrayList<>(driver.getAdditionalClasspathElements());
        elements.removeIf(element -> element.getClassesRootUrls().stream()
                .anyMatch(url -> url.contains("elasticsearch-rest-jdbc.jar") && !driverUrl.equals(url)));
        boolean alreadyAdded = elements.stream()
                .flatMap(element -> element.getClassesRootUrls().stream())
                .anyMatch(driverUrl::equals);
        if (alreadyAdded) {
            return;
        }

        elements.addAll(SimpleClasspathElementFactory.createElements(driverUrl));
        driver.setAdditionalClasspathElements(elements);
        manager.updateDriver(driver);
        LOG.info("Configured bundled Elasticsearch REST JDBC driver");
    }

    private static Path locateBundledDriverJar() {
        try {
            Path start = Path.of(BundledDriverStartupActivity.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path current = Files.isDirectory(start) ? start : start.getParent();
            while (current != null) {
                Path candidate = current.resolve(DRIVER_JAR);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                current = current.getParent();
            }
        } catch (Exception e) {
            LOG.warn("Cannot locate bundled Elasticsearch REST JDBC driver", e);
        }
        return null;
    }
}
