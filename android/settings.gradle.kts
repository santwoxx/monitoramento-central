pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DigitalTwinAndroid"
include(":app")
include(":core-database")
include(":core-network")
include(":core-media")
include(":core-state")
include(":feature-overlay")
