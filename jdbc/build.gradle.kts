plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.9"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

dependencies {
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.82")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("elasticsearch-rest-jdbc.jar")
    mergeServiceFiles()
    minimize {
        // Jackson discovers serializers/deserializers reflectively.
        exclude(dependency("com.fasterxml.jackson.*:.*:.*"))
    }
}

artifacts {
    add("archives", tasks.shadowJar)
}
