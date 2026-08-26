plugins {
    java
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.spotless)
}

version =
    providers
        .gradleProperty("standaloneVersion")
        .orElse(
            // The standalone client version is owned by standalone-client/version.json
            // so the standalone module builds cannot drift from the released client version.
            (groovy.json.JsonSlurper().parse(file("../version.json")) as Map<*, *>)["version"].toString(),
        ).get()

base {
    archivesName = "AGMA-Standalone-Ui-Common"
}

repositories {
    maven("https://maven.blamejared.com") {
        name = "BlameJared"
        content {
            includeGroup("mezz.jei")
        }
    }
}

dependencies {
    minecraft(libs.minecraft1182)
    mappings(loom.officialMojangMappings())

    api(project(":standalone-client:fabric-common"))
    modCompileOnly(libs.jei.api1182)
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

val verifyUiCommonBoundary by tasks.registering {
    group = "verification"
    description = "Ensures shared Minecraft 1.18.2 UI code stays loader-agnostic."
    doLast {
        val forbidden = listOf("net.fabricmc", "net.minecraftforge", "dev.emi")
        val violations =
            fileTree("src/main/java") {
                include("**/*.java")
            }.files.flatMap { source ->
                val text = source.readText(Charsets.UTF_8)
                forbidden.filter(text::contains).map { name ->
                    "${source.relativeTo(projectDir)} references $name"
                }
            }
        check(violations.isEmpty()) {
            "Ui common crossed a mod-loader boundary:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.check {
    dependsOn(verifyUiCommonBoundary)
}
