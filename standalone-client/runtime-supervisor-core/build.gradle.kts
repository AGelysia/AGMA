plugins {
    `java-library`
    alias(libs.plugins.spotless)
}

version = providers.gradleProperty("standaloneVersion").orElse("0.3.2").get()

base {
    archivesName = "AGMA-Standalone-Runtime-Supervisor-Core"
}

repositories {
    mavenCentral()
}

val standaloneDirectory = rootProject.layout.projectDirectory.dir("standalone-client")

dependencies {
    implementation(libs.gson)

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

val verifySupervisorBoundary by tasks.registering {
    group = "verification"
    description = "Ensures the supervisor core remains platform-neutral and Java 17 compatible."
    doLast {
        val compileDependencies =
            configurations.compileClasspath
                .get()
                .allDependencies
                .map { "${it.group}:${it.name}" }
                .toSet()
        check(compileDependencies == setOf("com.google.code.gson:gson")) {
            "runtime supervisor compile dependencies are not the reviewed set: $compileDependencies"
        }

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
            "runtime supervisor crossed a platform boundary:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.file(standaloneDirectory.file("managed-runtime/node-distributions.json"))
    systemProperty("minecraftAgent.standaloneDir", standaloneDirectory.asFile.absolutePath)
}

tasks.check {
    dependsOn(verifySupervisorBoundary)
}
