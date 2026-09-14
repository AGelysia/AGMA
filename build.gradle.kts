import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
    alias(libs.plugins.spotless) apply false
}

// The server product version is owned by agent-runtime/package.json so the
// runtime npm package and the JVM artifacts cannot drift apart.
val serverVersion =
    (groovy.json.JsonSlurper().parse(file("agent-runtime/package.json")) as Map<*, *>)["version"].toString()

allprojects {
    group = "dev.minecraftagent"
    version = serverVersion

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs the JVM checks. Runtime checks are run with npm."
    dependsOn(
        ":paper-plugin:check",
        ":client-mod:check",
        ":standalone-client:core:check",
        ":standalone-client:runtime-supervisor-core:check",
        ":standalone-client:fabric-common:check",
        ":standalone-client:fabric-mc12111:check",
        ":standalone-client:fabric-mc1201:check",
        ":standalone-client:fabric-mc1182:check",
        ":standalone-client:forge-mc1182:check",
    )
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Formats the JVM projects. Runtime formatting is run with npm."
    dependsOn(
        ":paper-plugin:spotlessApply",
        ":client-mod:spotlessApply",
        ":standalone-client:core:spotlessApply",
        ":standalone-client:runtime-supervisor-core:spotlessApply",
        ":standalone-client:fabric-common:spotlessApply",
        ":standalone-client:fabric-mc12111:spotlessApply",
        ":standalone-client:fabric-mc1201:spotlessApply",
        ":standalone-client:fabric-mc1182:spotlessApply",
        ":standalone-client:forge-mc1182:spotlessApply",
    )
}
