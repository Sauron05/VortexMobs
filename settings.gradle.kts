pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "VortexMobs"

include(
    "vortexmobs-core",
    "vortexmobs-server",
    "vortexmobs-fabric-1_21_11",
    "vortexmobs-fabric-26_1_2"
)

project(":vortexmobs-server").projectDir = file("vortexmobs-bukkit")