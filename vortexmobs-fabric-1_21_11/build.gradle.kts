plugins {
    id("fabric-loom") version "1.16.1"
    id("com.gradleup.shadow") version "8.3.5"
}

base {
    archivesName.set("vortexmobs-fabric-1_21_11")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

val shadowBundle by configurations.creating

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft1211")}")
    mappings("net.fabricmc:yarn:${property("minecraft1211")}+build.1:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabricApi1211")}")

    implementation(project(":vortexmobs-core"))
    shadowBundle(project(":vortexmobs-core"))

    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
    shadowBundle("com.google.code.gson:gson:${property("gsonVersion")}")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "loaderVersion" to project.property("fabricLoaderVersion"),
        "minecraftVersion" to project.property("minecraft1211")
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("dev-shadow")
    configurations = listOf(shadowBundle)
}

tasks.remapJar {
    dependsOn(tasks.shadowJar)
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    archiveClassifier.set("")
}

val jarOutputDir = layout.projectDirectory.dir("jar")
tasks.register<Copy>("copyJar") {
    dependsOn(tasks.remapJar)
    from(tasks.remapJar.flatMap { it.archiveFile })
    into(jarOutputDir)
    doFirst {
        jarOutputDir.asFile.mkdirs()
    }
}

tasks.build {
    dependsOn(tasks.remapJar)
    finalizedBy("copyJar")
}