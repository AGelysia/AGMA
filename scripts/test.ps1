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
    & $Gradle @GradleArgs :paper-plugin:build
    if ($LASTEXITCODE -ne 0) { throw "Paper build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :client-mod:build
    if ($LASTEXITCODE -ne 0) { throw "Client build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :standalone-client:core:build
    if ($LASTEXITCODE -ne 0) { throw "Standalone client core build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :standalone-client:runtime-supervisor-core:build
    if ($LASTEXITCODE -ne 0) { throw "Standalone runtime supervisor build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :standalone-client:fabric-common:build
    if ($LASTEXITCODE -ne 0) { throw "Standalone Fabric common build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :standalone-client:fabric-mc12111:build
    if ($LASTEXITCODE -ne 0) { throw "Standalone Minecraft 1.21.11 build failed with exit code $LASTEXITCODE" }
    & $Gradle @GradleArgs :standalone-client:fabric-mc1182:build
    if ($LASTEXITCODE -ne 0) { throw "Standalone Minecraft 1.18.2 build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
