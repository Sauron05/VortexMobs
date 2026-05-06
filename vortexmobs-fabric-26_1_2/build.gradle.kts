plugins {
    id("net.fabricmc.fabric-loom") version "1.16.1"
    id("com.gradleup.shadow") version "8.3.5"
}

base {
    archivesName.set("vortexmobs-fabric-26_1_2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

val shadowBundle by configurations.creating

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft2612")}")
    implementation("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabricApi2612")}")

    implementation(project(":vortexmobs-core"))
    shadowBundle(project(":vortexmobs-core"))

    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
    shadowBundle("com.google.code.gson:gson:${property("gsonVersion")}")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "loaderVersion" to project.property("fabricLoaderVersion"),
        "minecraftVersion" to project.property("minecraft2612")
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
}

tasks.jar {
    archiveClassifier.set("dev")
}

val jarOutputDir = layout.projectDirectory.dir("jar")
tasks.register<Copy>("copyJar") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(jarOutputDir)
    doFirst {
        jarOutputDir.asFile.mkdirs()
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
    finalizedBy("copyJar")
}