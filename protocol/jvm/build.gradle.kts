import org.gradle.api.tasks.PathSensitivity

plugins {
    java
    alias(libs.plugins.spotless)
}

base {
    archivesName = "AGMA-Protocol-Jvm"
}

val protocolDirectory = rootProject.layout.projectDirectory.dir("protocol")

repositories {
    mavenCentral()
}

// The channel codecs are deliberately hand-rolled on top of the JDK only: both
// ends must agree on the framing grammar without pulling a JSON library into the
// Fabric client or the Paper plugin.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
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

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.dir(protocolDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(protocolDirectory.dir("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("minecraftAgent.protocolDir", protocolDirectory.asFile.absolutePath)
}
