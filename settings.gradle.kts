pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "datagrip-elasticsearch-rest"
include("jdbc", "plugin", "integration-test", "completion-codegen")
project(":plugin").name = "es-rest-data-source"
