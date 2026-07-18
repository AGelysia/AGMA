plugins {
    java
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.spotless)
}

version = providers.gradleProperty("standaloneVersion").orElse("0.3.2").get()

base {
    archivesName = "AGMA-Standalone-Client-mc1.18.2-fabric"
}

repositories {
    maven("https://maven.blamejared.com") {
        name = "BlameJared"
        content {
            includeGroup("mezz.jei")
        }
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content {
            includeGroup("maven.modrinth")
        }
    }
}

val minecraftVersion = libs.versions.minecraft1182.get()
val fabricApiVersion =
    libs.versions.fabric.api1182
        .get()
val loaderVersion =
    libs.versions.fabric.loader
        .get()

dependencies {
    minecraft(libs.minecraft1182)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api1182)
    modCompileOnly(libs.jei.api1182)
    modCompileOnly(libs.emi1182)

    implementation(project(":standalone-client:core"))
    implementation(project(":standalone-client:runtime-supervisor-core"))
    implementation(project(":standalone-client:fabric-common"))
    include(project(":standalone-client:core"))
    include(project(":standalone-client:runtime-supervisor-core"))
    include(project(":standalone-client:fabric-common"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.gson)
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

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("fabricApiVersion", fabricApiVersion)
    inputs.property("loaderVersion", loaderVersion)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to version,
            "minecraftVersion" to minecraftVersion,
            "fabricApiVersion" to fabricApiVersion,
            "loaderVersion" to loaderVersion,
        )
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
    description = "Rejects Minecraft server-payload and viewer coupling in the standalone shell."
    doLast {
        val forbidden =
            listOf(
                "ClientPlayNetworking",
                "ServerPlayNetworking",
                "PayloadTypeRegistry",
                "CustomPacketPayload",
                "mezz.jei",
                "dev.emi",
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
            "standalone main path crossed its local-only/optional-viewer boundary:\n${violations.joinToString("\n")}"
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
