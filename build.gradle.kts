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
    dependsOn(":paper-plugin:check", ":client-mod:check")
}

tasks.register("formatAll") {
    group = "formatting"
    description = "Formats the JVM projects. Runtime formatting is run with npm."
    dependsOn(":paper-plugin:spotlessApply", ":client-mod:spotlessApply")
}
