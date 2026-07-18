import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "dev.minecraftagent"
    version = "0.1.0"

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
        ":standalone-client:fabric-mc1182:spotlessApply",
        ":standalone-client:forge-mc1182:spotlessApply",
    )
}
