plugins {
    java
}

group = "me.custommobs"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // API de Paper 26.1.2 (Purpur es compatible con ella). Si falla la resolución, prueba con
    // "io.papermc.paper:paper-api:26.1.2.build.66-stable"
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
