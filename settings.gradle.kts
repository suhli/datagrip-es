pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "datagrip-elasticsearch-rest"
include("jdbc", "plugin", "integration-test")
project(":plugin").name = "datagrip-elasticsearch-rest-plugin"
