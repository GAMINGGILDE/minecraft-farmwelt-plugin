plugins {
    java
}

group = "de.minecraftgilde"

val releaseVersion = providers.gradleProperty("releaseVersion")
    .orElse("2.1.0-SNAPSHOT")

version = releaseVersion.get()

val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "thenextlvl"
        url = uri("https://repo.thenextlvl.net/releases")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("net.thenextlvl:worlds:4.4.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("net.thenextlvl:worlds:4.4.1")
    testCompileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Farmwelt")
}
