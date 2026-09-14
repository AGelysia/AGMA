$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Gradle = if ($env:OS -eq "Windows_NT") { "$Root/gradlew.bat" } else { "$Root/gradlew" }
$GradleArgs = @("--no-daemon", "--max-workers=1")
if ($env:MINECRAFT_AGENT_NO_BUILD_CACHE -eq "1") {
    $GradleArgs += "--no-build-cache"
}

Push-Location "$Root/agent-runtime"
try {
    npm ci --prefer-offline
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    npm run format:check
    if ($LASTEXITCODE -ne 0) { throw "npm run format:check failed with exit code $LASTEXITCODE" }
    npm run lint
    if ($LASTEXITCODE -ne 0) { throw "npm run lint failed with exit code $LASTEXITCODE" }
    npm test
    if ($LASTEXITCODE -ne 0) { throw "npm test failed with exit code $LASTEXITCODE" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

Push-Location $Root
try {
    # Same version alignment rules as the Linux job; the implementation is shared.
    node "$Root/scripts/check-versions.mjs"
    if ($LASTEXITCODE -ne 0) { throw "check-versions failed with exit code $LASTEXITCODE" }

    # One Gradle invocation: multi-project configuration (Loom especially) is the
    # dominant cost, so paying it once per module is what made CI slow. Windows runs
    # the same targets as Linux so test results exist for every module on both OSes.
    $Targets = @(
        ":protocol:jvm:build",
        ":paper-plugin:build",
        ":client-mod:build",
        ":standalone-client:core:build",
        ":standalone-client:runtime-supervisor-core:build",
        ":standalone-client:fabric-common:build",
        ":standalone-client:ui-common:build",
        ":standalone-client:fabric-mc12111:build",
        ":standalone-client:fabric-mc1201:build",
        ":standalone-client:fabric-mc1182:build",
        ":standalone-client:forge-mc1182:build"
    )
    & $Gradle @GradleArgs @Targets
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
