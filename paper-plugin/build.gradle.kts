import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Jar
import java.security.MessageDigest

plugins {
    java
    alias(libs.plugins.spotless)
}

base {
    archivesName = "AGMA-Server-Plugin"
}

val pluginVersion = version.toString()
val protocolDirectory = rootProject.layout.projectDirectory.dir("protocol")
val runtimeConfigTemplate = rootProject.layout.projectDirectory.file("agent-runtime/config.example.yml")
val embedded by configurations.creating
val managedRuntimePlatform = "linux-x86_64"
val managedRuntimeOutput =
    rootProject.layout.buildDirectory.dir("managed-runtime/$managedRuntimePlatform")
val managedRuntimeArchive = managedRuntimeOutput.map { it.file("sidecar.zip") }

configurations.named("implementation") {
    extendsFrom(embedded)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
}

dependencies {
    embedded(libs.json.canonicalization)
    compileOnly(libs.paper.api)
    compileOnly(libs.gson)
    compileOnly(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.byte.buddy)
    testImplementation(libs.byte.buddy.agent)
    testImplementation(libs.gson)
    testImplementation(libs.java.websocket)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.mockito)
    testImplementation(libs.paper.api)
    testImplementation(libs.snakeyaml)
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
    inputs.property("version", pluginVersion)
    inputs.property("managedRuntimeTemplateTransformVersion", 3)
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
    from(protocolDirectory.dir("schemas")) {
        into("protocol/schemas")
    }
    from(runtimeConfigTemplate) {
        into("managed-runtime")
        rename { "config.template.yml" }
        filter { line ->
            when {
                line == "  id: survival-main" -> {
                    "  id: \${AGMA_MANAGED_SERVER_ID}"
                }

                line == "  port: 38127" -> {
                    "  port: \${AGMA_MANAGED_RUNTIME_PORT}"
                }

                line.contains("serverToken: \${MINECRAFT_AGENT_SERVER_TOKEN}") -> {
                    line.replace("\${MINECRAFT_AGENT_SERVER_TOKEN}", "\${AGMA_MANAGED_SERVER_TOKEN}")
                }

                line.contains("baseUrl: \${OPENAI_BASE_URL}") -> {
                    line.replace("\${OPENAI_BASE_URL}", "https://api.openai.com/v1")
                }

                line.contains("baseUrl: \${ANTHROPIC_BASE_URL}") -> {
                    line.replace("\${ANTHROPIC_BASE_URL}", "https://api.anthropic.com/v1")
                }

                line.contains("baseUrl: \${DEEPSEEK_BASE_URL}") -> {
                    line.replace("\${DEEPSEEK_BASE_URL}", "https://api.deepseek.com")
                }

                line.contains("baseUrl: \${GEMINI_BASE_URL}") -> {
                    line.replace(
                        "\${GEMINI_BASE_URL}",
                        "https://generativelanguage.googleapis.com/v1beta",
                    )
                }

                line.contains("baseUrl: \${OPENAI_COMPATIBLE_BASE_URL}") -> {
                    line.replace("\${OPENAI_COMPATIBLE_BASE_URL}", "replace-with-provider-base-url")
                }

                line.contains("apiKey: \${") -> {
                    line.substringBefore("apiKey:") + "apiKey: replace-with-provider-api-key"
                }

                line.contains("shown whole-value environment reference") -> {
                    line.replace("shown whole-value environment reference", "shown literal URL")
                }

                else -> {
                    line
                }
            }
        }
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
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from({ embedded.files.map(::zipTree) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    manifest.attributes["Implementation-Version"] = pluginVersion
}

val buildManagedRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the pinned Linux x86_64 managed Runtime sidecar."
    workingDir(rootProject.layout.projectDirectory)
    commandLine(rootProject.file("scripts/build-managed-runtime.sh"))
    environment("MINECRAFT_AGENT_MANAGED_OUTPUT", managedRuntimeOutput.get().asFile.absolutePath)

    inputs.file(rootProject.file("managed-runtime/node-distributions.json"))
    inputs.file(rootProject.file("scripts/build-managed-runtime.sh"))
    inputs.file(rootProject.file("scripts/verify-managed-runtime.sh"))
    inputs
        .files(
            rootProject.fileTree("agent-runtime") {
                include("package.json", "package-lock.json", "tsconfig.json", "src/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(protocolDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(managedRuntimeArchive)
    outputs.file(managedRuntimeOutput.map { it.file("sidecar-manifest.json") })
    outputs.file(managedRuntimeOutput.map { it.file("SHA256SUMS") })
}

val managedOfflineResources = layout.buildDirectory.dir("generated/managed-offline-resources")
val prepareManagedOfflineResources by tasks.registering {
    group = "distribution"
    description = "Generates integrity metadata for the embedded managed Runtime sidecar."
    dependsOn(buildManagedRuntime)
    inputs.file(managedRuntimeArchive)
    outputs.dir(managedOfflineResources)

    doLast {
        val archive = managedRuntimeArchive.get().asFile
        val outputDirectory = managedOfflineResources.get().dir("managed-runtime").asFile
        project.delete(managedOfflineResources.get())
        check(outputDirectory.mkdirs()) { "Could not create managed Runtime resource directory" }
        archive.copyTo(outputDirectory.resolve("sidecar.zip"), overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        val sha256 =
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        outputDirectory.resolve("artifact.properties").writeText(
            """
            schemaVersion=1
            resourceName=managed-runtime/sidecar.zip
            byteSize=${archive.length()}
            sha256=$sha256
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

val regularJar = tasks.named<Jar>("jar")
tasks.register<Jar>("managedOfflineJar") {
    group = "distribution"
    description = "Builds a Paper plugin JAR with the Linux x86_64 managed Runtime embedded."
    dependsOn(regularJar, prepareManagedOfflineResources)
    archiveBaseName.set("AGMA-Server-Integrated")
    archiveClassifier.set("mc1.21.11-$managedRuntimePlatform")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(regularJar.map { zipTree(it.archiveFile.get().asFile) })
    from(managedOfflineResources)
    manifest.attributes["Implementation-Version"] = pluginVersion
}
