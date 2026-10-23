plugins {
    java
}

group = "mx.vicko"
version = "0.2.45"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.206-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
