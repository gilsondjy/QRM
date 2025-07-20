pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application")       version "8.7.3"
        id("org.jetbrains.kotlin.android") version "1.9.10"
        id("com.google.gms.google-services") version "4.3.15"
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "QRM"
include(":app")
