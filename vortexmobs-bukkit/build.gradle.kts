plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

base {
    archivesName.set("vortexmobs-server")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    implementation(project(":vortexmobs-core"))
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
    compileOnly("org.spigotmc:spigot-api:${property("spigotApiVersion")}")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
}

val jarOutputDir = layout.projectDirectory.dir("jar")
tasks.register<Copy>("copyJar") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(jarOutputDir)
    doFirst {
        jarOutputDir.asFile.mkdirs()
        delete(fileTree(jarOutputDir) {
            include("vortexmobs-bukkit-*.jar")
        })
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
    finalizedBy("copyJar")
}