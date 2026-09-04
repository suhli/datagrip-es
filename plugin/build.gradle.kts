import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

base {
    archivesName.set("es-rest-data-source")
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
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("com.intellij.database")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    compileOnly(project(":jdbc"))
    runtimeOnly(project(path = ":jdbc", configuration = "shadow"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    testImplementation(project(":jdbc"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

val completionResources = configurations.create("completionResources")
dependencies {
    completionResources(project(path = ":completion-codegen", configuration = "generated"))
}

val copyCompletionResources by tasks.registering(Copy::class) {
    from(completionResources)
    into(layout.buildDirectory.dir("generated/completion-resources/completion"))
    dependsOn(":completion-codegen:generateCompletionResources")
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/completion-resources"))
        }
    }
}

tasks.named("processResources") {
    dependsOn(copyCompletionResources)
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "junit-vintage")
    }
    dependsOn(copyCompletionResources)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = "ES REST Data Source"
        version = providers.gradleProperty("pluginVersion")
        changeNotes = releaseNotes(providers.gradleProperty("pluginVersion").get())
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        // verifyPluginSignature in the 2.7.x plugin line requires a certificate file.
        certificateChainFile.set(
            layout.file(providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map(::file)),
        )
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

fun releaseNotes(version: String): String {
    val changelog = rootProject.file("CHANGELOG.md")
    val content = changelog.readText()
    val header = Regex("(?m)^## " + Regex.escape(version) + "\\s*$")
    val start = header.find(content)
        ?: error("CHANGELOG.md has no section for plugin version $version")
    val bodyStart = start.range.last + 1
    val bodyEnd = Regex("(?m)^## ").find(content, bodyStart)?.range?.first ?: content.length
    return content.substring(bodyStart, bodyEnd).trim()
}

tasks.buildSearchableOptions {
    enabled = false
}

tasks.named("verifyPluginSignature") {
    // Gradle 8.13 validates that the signed ZIP is produced before verification.
    dependsOn(tasks.named("signPlugin"))
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
