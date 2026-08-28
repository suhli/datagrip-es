plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation(project(path = ":jdbc", configuration = "shadow"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    onlyIf {
        providers.environmentVariable("ES_TEST_URL").isPresent
    }
    environment("ES_TEST_URL", providers.environmentVariable("ES_TEST_URL").orNull ?: "")
    environment("ES_TEST_USER", providers.environmentVariable("ES_TEST_USER").orNull ?: "")
    environment("ES_TEST_PASSWORD", providers.environmentVariable("ES_TEST_PASSWORD").orNull ?: "")
}
