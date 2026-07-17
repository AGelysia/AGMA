$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $IsLinux) {
    throw "AGMA release packaging requires Linux x86_64 because the integrated JAR embeds a Linux Runtime."
}

& bash "$Root/scripts/package.sh"
if ($LASTEXITCODE -ne 0) {
    throw "AGMA packaging failed with exit code $LASTEXITCODE"
}
