import net.fabricmc.loom.build.nesting.NestableJarGenerationTask
import net.fabricmc.loom.util.ZipReprocessorUtil

plugins {
    java
    alias(libs.plugins.architectury.loom)
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
    archivesName = "AGMA-Standalone-Client-mc1.18.2-forge"
}

repositories {
    maven("https://maven.blamejared.com") {
        name = "BlameJared"
        content {
            includeGroup("mezz.jei")
        }
    }
}

val minecraftVersion = libs.versions.minecraft1182.get()
val forgeVersion = libs.versions.forge1182.get()

loom {
    silentMojangMappingsLicense()
}

dependencies {
    minecraft(libs.minecraft1182)
    mappings(loom.officialMojangMappings())
    forge(libs.forge1182)
    modCompileOnly(libs.jei.api1182.forge)
    modRuntimeOnly(libs.jei1182.forge) {
        isTransitive = false
    }

    implementation(project(":standalone-client:core"))
    implementation(project(":standalone-client:runtime-supervisor-core"))
    implementation(project(":standalone-client:fabric-common"))
    forgeRuntimeLibrary(project(":standalone-client:core"))
    forgeRuntimeLibrary(project(":standalone-client:runtime-supervisor-core"))
    forgeRuntimeLibrary(project(":standalone-client:fabric-common"))
    include(project(":standalone-client:core"))
    include(project(":standalone-client:runtime-supervisor-core"))
    include(project(":standalone-client:fabric-common"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testImplementation(libs.night.config.toml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

// The loader-agnostic Minecraft 1.18.2 screens and catalog service are compiled from the
// ui-common sources into this jar so Architectury Loom remaps them with this loader's SRG
// runtime namespace; a shared prebuilt jar cannot be remapped per loader.
sourceSets.main {
    java.srcDir(project(":standalone-client:ui-common").projectDir.resolve("src/main/java"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("forgeVersion", forgeVersion)
    filesMatching("META-INF/mods.toml") {
        expand(
            "version" to version,
            "minecraftVersion" to minecraftVersion,
            "forgeVersion" to forgeVersion.substringAfter('-'),
        )
    }
}

tasks.named<NestableJarGenerationTask>("processIncludeJars") {
    doLast {
        outputDirectory
            .get()
            .asFile
            .listFiles { file -> file.extension == "jar" }
            ?.sortedBy { it.name }
            ?.forEach { jar ->
                ZipReprocessorUtil.reprocessZip(jar.toPath(), true, false)
            }
    }
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

val verifyStandaloneBoundary by tasks.registering {
    group = "verification"
    description = "Rejects Fabric, server-payload, EMI, and mandatory-viewer coupling in the Forge shell."
    doLast {
        val forbidden =
            listOf(
                "net.fabricmc",
                "dev.emi",
                "ClientPlayNetworking",
                "ServerPlayNetworking",
                "PayloadTypeRegistry",
                "CustomPacketPayload",
                "mezz.jei",
            )
        val violations =
            fileTree("src/main/java") {
                include("**/*.java")
                exclude("**/viewer/**")
            }.files.flatMap { source ->
                val text = source.readText(Charsets.UTF_8)
                forbidden.filter(text::contains).map { name ->
                    "${source.relativeTo(projectDir)} references $name"
                }
            }
        check(violations.isEmpty()) {
            "standalone Forge path crossed its client-only/optional-viewer boundary:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("agma.expectedVersion", version.toString())
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "AGMA Standalone Client",
            "Implementation-Version" to version,
        )
    }
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_agma_standalone" }
    }
}

tasks.check {
    dependsOn(verifyStandaloneBoundary)
}
