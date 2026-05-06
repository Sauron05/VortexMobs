plugins {
    base
}

val releaseVersion = providers.gradleProperty("pluginVersion").get()
val releaseJarDir = layout.projectDirectory.dir("jar")

allprojects {
    group = "com.sauron.vortexmobs"
    version = providers.gradleProperty("pluginVersion").get()
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

tasks.register<Copy>("copyAllJars") {
    dependsOn(
        ":vortexmobs-server:copyJar",
        ":vortexmobs-fabric-1_21_11:copyJar",
        ":vortexmobs-fabric-26_1_2:copyJar"
    )

    into(releaseJarDir)

    from(project(":vortexmobs-server").layout.projectDirectory.file("jar/vortexmobs-server-$releaseVersion.jar")) {
        rename { "VortexMobs-paper-folia-$releaseVersion.jar" }
    }
    from(project(":vortexmobs-server").layout.projectDirectory.file("jar/vortexmobs-server-$releaseVersion.jar")) {
        rename { "VortexMobs-purpur-$releaseVersion.jar" }
    }
    from(project(":vortexmobs-server").layout.projectDirectory.file("jar/vortexmobs-server-$releaseVersion.jar")) {
        rename { "VortexMobs-spigot-$releaseVersion.jar" }
    }
    from(project(":vortexmobs-fabric-1_21_11").layout.projectDirectory.file("jar/vortexmobs-fabric-1_21_11-$releaseVersion.jar")) {
        rename { "VortexMobs-fabric-1.21.x-$releaseVersion.jar" }
    }
    from(project(":vortexmobs-fabric-26_1_2").layout.projectDirectory.file("jar/vortexmobs-fabric-26_1_2-$releaseVersion.jar")) {
        rename { "VortexMobs-fabric-26.x-$releaseVersion.jar" }
    }

    doFirst {
        releaseJarDir.asFile.mkdirs()
    }
}

tasks.register<Zip>("zipRelease") {
    dependsOn("copyAllJars")
    archiveBaseName.set("VortexMobs-release")
    archiveVersion.set(releaseVersion)
    destinationDirectory.set(releaseJarDir)

    from(releaseJarDir) {
        include("VortexMobs-*.jar")
    }
    from(layout.projectDirectory.file("README.md"))
}

tasks.build {
    finalizedBy("zipRelease")
}