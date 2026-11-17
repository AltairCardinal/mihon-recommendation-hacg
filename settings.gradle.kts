pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.diffplug.spotless") {
                useModule("com.diffplug.spotless:spotless-plugin-gradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("recommendationSdkRepository").get()) }
        mavenCentral()
    }
}
rootProject.name = "mihon-recommendation-hacg"
