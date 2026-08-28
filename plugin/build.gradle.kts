import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

base {
    archivesName.set("datagrip-elasticsearch-rest")
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("com.intellij.database")
    }
    runtimeOnly(project(path = ":jdbc", configuration = "shadow"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = "Elasticsearch REST"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // The JDBC fat JAR is loaded by DataGrip's driver class loader. These
        // bundled libraries are not IntelliJ API consumers and are verified by
        // the JDBC unit/integration tests instead.
        externalPrefixes = listOf("org.apache", "com.fasterxml.jackson", "org.slf4j")
        // Compatibility/deprecation reports include the standalone JDBC fat
        // JAR's third-party bytecode. Fail only for plugin descriptor or
        // IntelliJ internal-API violations; JDBC runtime compatibility is
        // covered by its isolated tests.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
        )
        ides {
            create(IntelliJPlatformType.DataGrip, "2025.1")
            create(IntelliJPlatformType.DataGrip, "2026.1")
            create(IntelliJPlatformType.DataGrip, "2026.2")
        }
    }
}

tasks.buildSearchableOptions {
    enabled = false
}
