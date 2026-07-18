plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

version = providers.gradleProperty("standaloneVersion").orElse("0.3.0").get()

base {
    archivesName = "AGMA-Standalone-Fabric-Common"
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":standalone-client:core"))
    api(project(":standalone-client:runtime-supervisor-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
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

val verifyFabricCommonBoundary by tasks.registering {
    group = "verification"
    description = "Ensures common standalone lifecycle code has no game or viewer dependencies."
    doLast {
        val forbidden = listOf("net.minecraft", "net.fabricmc", "mezz.jei", "dev.emi")
        val violations =
            fileTree("src/main/java") {
                include("**/*.java")
            }.files.flatMap { source ->
                val text = source.readText(Charsets.UTF_8)
                forbidden.filter(text::contains).map { name ->
                    "${source.relativeTo(projectDir)} imports $name"
                }
            }
        check(violations.isEmpty()) {
            "Fabric common crossed a platform boundary:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
}

tasks.check {
    dependsOn(verifyFabricCommonBoundary)
}
