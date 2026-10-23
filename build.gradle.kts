import org.gradle.api.tasks.bundling.Zip

plugins {
    java
}

group = "mx.vicko"
version = "0.2.45"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.206-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.206-stable")
    // Harness mínimo Paso 1: solo JUnit 5. No se asume MockBukkit ni Paper test harness.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// Empaquetado reproducible del resource pack: pack.mcmeta queda en la raíz
// del ZIP, sin duplicar la carpeta resource-pack. El JAR y el ZIP comparten
// versión documentada en README. Requiere JDK 25 (toolchain) y Paper 26.3.
val resourcePackZip = tasks.register("buildResourcePack", Zip::class.java) {
    group = "build"
    description = "Empaqueta resource-pack/ como Letumcraft-ResourcePack-<version>.zip."
    from("resource-pack")
    archiveFileName.set("Letumcraft-ResourcePack-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named("build") {
    dependsOn(resourcePackZip)
}
