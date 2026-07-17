import org.gradle.api.tasks.PathSensitivity

plugins {
    java
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.spotless)
}

base {
    archivesName = "AGMA-Client"
}

val modVersion = version.toString()
val minecraftVersion = libs.versions.minecraft.get()
val fabricApiVersion =
    libs.versions.fabric.api
        .get()
val protocolDirectory = rootProject.layout.projectDirectory.dir("protocol")

repositories {
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    implementation(libs.json.canonicalization)
    include(libs.json.canonicalization)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

sourceSets.test {
    java.srcDir(rootProject.file("protocol/jvm-test/src/test/java"))
}

spotless {
    java {
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        )
        target("src/**/*.java")
    }
    kotlinGradle {
        ktlint()
        target("*.gradle.kts")
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", modVersion)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("fabricApiVersion", fabricApiVersion)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersion,
            "minecraftVersion" to minecraftVersion,
            "fabricApiVersion" to fabricApiVersion,
        )
    }
    from(protocolDirectory.dir("schemas")) {
        into("protocol/schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.dir(protocolDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(protocolDirectory.dir("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("minecraftAgent.protocolDir", protocolDirectory.asFile.absolutePath)
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_${project.name}" }
    }
}
