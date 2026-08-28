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
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "default").substringBefore('.').ifEmpty { "default" })
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

fun loadDotEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val separator = line.indexOf('=')
            val key = line.substring(0, separator).trim()
            var value = line.substring(separator + 1).trim()
            if (
                (value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }
}

val dotenv = loadDotEnv(rootProject.file(".env"))
val localDataGripPath = sequenceOf(
    System.getenv("LOCAL_DATAGRIP_PATH"),
    dotenv["LOCAL_DATAGRIP_PATH"],
    providers.gradleProperty("localDataGripPath").orNull,
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

if (localDataGripPath != null) {
    intellijPlatformTesting {
        runIde {
            register("runDataGrip") {
                localPath = file(localDataGripPath)
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if ((name == "runIde" || name == "runDataGrip") && project.hasProperty("debugIde")) {
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
    }
}
