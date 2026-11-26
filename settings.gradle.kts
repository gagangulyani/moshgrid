pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Google's mirror of Maven Central, tried when Central throttles CI runners. Every artifact is
        // still checked against gradle/verification-metadata.xml, so a mirror can't change what we build.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Google's mirror of Maven Central, tried when Central throttles CI runners. Every artifact is
        // still checked against gradle/verification-metadata.xml, so a mirror can't change what we build.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}

rootProject.name = "bitchat-android"
include(":app")
// include(":wear")  MoshGrid: Wear OS app not shipped
// Using published Arti AAR; local module not included
