$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Config = if ($env:AGMA_RUNTIME_CONFIG) { $env:AGMA_RUNTIME_CONFIG } else { Join-Path $Root "runtime/config.yml" }
$RuntimeArgs = @($args)

if ($RuntimeArgs.Count -eq 0) {
    $RuntimeArgs = @("--config", $Config)
}

if (-not (Test-Path $Config) -and $RuntimeArgs.Count -eq 2 -and $RuntimeArgs[0] -eq "--config" -and $RuntimeArgs[1] -eq $Config) {
    throw "Runtime configuration is missing: $Config. Create it from runtime/config.example.yml."
}

$NodeVersion = node -p "process.versions.node"
if ($LASTEXITCODE -ne 0) {
    throw "Node.js 22.16.0 or newer, but lower than 23, is required."
}
$VersionParts = $NodeVersion.Split(".")
if ([int]$VersionParts[0] -ne 22 -or [int]$VersionParts[1] -lt 16) {
    throw "Node.js 22.16.0 or newer, but lower than 23, is required."
}

& node "$Root/runtime/dist/bootstrap/index.js" @RuntimeArgs
if ($LASTEXITCODE -ne 0) {
    throw "AGMA Runtime failed with exit code $LASTEXITCODE"
}
