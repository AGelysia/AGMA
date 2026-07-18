import org.gradle.api.tasks.PathSensitivity

plugins {
    java
    alias(libs.plugins.spotless)
}

version = providers.gradleProperty("standaloneVersion").orElse("0.3.1").get()

base {
    archivesName = "AGMA-Standalone-Client-Core"
}

repositories {
    mavenCentral()
}

val standaloneDirectory = rootProject.layout.projectDirectory.dir("standalone-client")
val contractDirectory = standaloneDirectory.dir("contracts")

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.json.canonicalization)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks.named<JavaCompile>("compileJava") {
    options.release = 17
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

val verifyCoreBoundary by tasks.registering {
    group = "verification"
    description = "Ensures the standalone domain core has no platform or viewer dependencies."
    doLast {
        val dependencies = configurations.compileClasspath.get().allDependencies
        check(dependencies.isEmpty()) {
            "standalone-client core must not have compile dependencies: " +
                dependencies.joinToString { "${it.group}:${it.name}:${it.version}" }
        }

        val forbiddenImports =
            listOf("net.minecraft", "net.fabricmc", "mezz.jei", "dev.emi")
        val violations =
            fileTree("src/main/java") {
                include("**/*.java")
            }.files.flatMap { source ->
                val text = source.readText(Charsets.UTF_8)
                forbiddenImports
                    .filter(text::contains)
                    .map { forbidden -> "${source.relativeTo(projectDir)} imports $forbidden" }
            }
        check(violations.isEmpty()) {
            "standalone-client core crossed a platform boundary:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.dir(contractDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(contractDirectory.dir("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(standaloneDirectory.dir("benchmarks")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(standaloneDirectory.dir("evaluation")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("minecraftAgent.protocolDir", contractDirectory.asFile.absolutePath)
    systemProperty("minecraftAgent.standaloneDir", standaloneDirectory.asFile.absolutePath)
}

tasks.check {
    dependsOn(verifyCoreBoundary)
}
