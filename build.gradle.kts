plugins {
    base
}

allprojects {
    group = providers.gradleProperty("pluginGroup").get()
    version = providers.gradleProperty("pluginVersion").get()

    repositories {
        mavenCentral()
    }
}
