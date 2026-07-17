param(
    [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$RuntimeVersion,
    [Parameter(Mandatory = $true)][string]$NodeVersion
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not [System.IO.Path]::IsPathFullyQualified($ReleaseDirectory)) {
    throw "Standalone release path must be absolute."
}
$Release = [System.IO.Path]::GetFullPath($ReleaseDirectory)
if (-not (Test-Path -LiteralPath $Release -PathType Container)) {
    throw "Standalone release directory is missing."
}

$MinecraftVersions = @("1.18.2", "1.21.11")
$Platforms = @("linux-x86_64", "windows-x86_64")
$ChecksumName = "AGMA-Client-Standalone-$Version-SHA256SUMS"
$SbomName = "AGMA-Client-Standalone-$Version-SBOM.cdx.json"
$Expected = [System.Collections.Generic.List[string]]::new()
foreach ($Minecraft in $MinecraftVersions) {
    foreach ($Platform in $Platforms) {
        $Expected.Add("AGMA-Client-Standalone-$Version-mc$Minecraft-fabric-$Platform.jar")
    }
}
$Expected.Add($SbomName)
$Expected.Add($ChecksumName)
$Actual = @(Get-ChildItem -LiteralPath $Release -Force | ForEach-Object { $_.Name } | Sort-Object)
if ([string]::Join("`n", ($Expected | Sort-Object)) -ne [string]::Join("`n", $Actual)) {
    throw "Standalone release does not contain the exact reviewed asset set."
}

$Checksums = @{}
foreach ($Line in Get-Content -LiteralPath (Join-Path $Release $ChecksumName)) {
    if ($Line -notmatch '^([0-9a-f]{64})  ([^/\\\x00-\x1f]+)$') {
        throw "Standalone checksum manifest contains a malformed line."
    }
    if ($Checksums.ContainsKey($Matches[2])) {
        throw "Standalone checksum manifest contains a duplicate asset."
    }
    $Checksums[$Matches[2]] = $Matches[1]
}
if ($Checksums.Count -ne 5) {
    throw "Standalone checksum manifest does not cover exactly four JARs and one SBOM."
}

$Sbom = Get-Content -LiteralPath (Join-Path $Release $SbomName) -Raw | ConvertFrom-Json
$SbomFields = @($Sbom.PSObject.Properties.Name | Sort-Object)
$ExpectedSbomFields = @(
    '$schema',
    'bomFormat',
    'components',
    'dependencies',
    'metadata',
    'specVersion',
    'version'
) | Sort-Object
if (
    [string]::Join("`n", $SbomFields) -ne [string]::Join("`n", $ExpectedSbomFields) -or
    $Sbom.'$schema' -ne 'https://cyclonedx.org/schema/bom-1.7.schema.json' -or
    $Sbom.bomFormat -ne 'CycloneDX' -or
    $Sbom.specVersion -ne '1.7' -or
    $Sbom.version -ne 1 -or
    $Sbom.metadata.component.name -ne 'AGMA Client Standalone' -or
    $Sbom.metadata.component.version -ne $Version -or
    @($Sbom.components).Count -lt 9 -or
    @($Sbom.dependencies).Count -lt 10
) {
    throw "Standalone CycloneDX SBOM identity or component inventory is invalid."
}
foreach ($Name in $Expected | Where-Object { $_ -ne $ChecksumName }) {
    $Path = Join-Path $Release $Name
    $Digest = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Checksums[$Name] -ne $Digest) {
        throw "Standalone release checksum mismatch: $Name"
    }
}

function Read-ZipEntryBytes {
    param(
        [Parameter(Mandatory = $true)][System.IO.Compression.ZipArchive]$Archive,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $Entries = @($Archive.Entries | Where-Object { $_.FullName -eq $Name })
    if ($Entries.Count -ne 1 -or $Entries[0].Length -lt 1) {
        throw "ZIP entry is missing, duplicated, or empty: $Name"
    }
    $Input = $Entries[0].Open()
    try {
        $Output = [System.IO.MemoryStream]::new()
        try {
            $Input.CopyTo($Output)
            return $Output.ToArray()
        } finally {
            $Output.Dispose()
        }
    } finally {
        $Input.Dispose()
    }
}

function Get-Sha256Bytes {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $Hash = [System.Security.Cryptography.SHA256]::HashData($Bytes)
    return [Convert]::ToHexString($Hash).ToLowerInvariant()
}

$Temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("agma-standalone-windows-" + [Guid]::NewGuid())
[System.IO.Directory]::CreateDirectory($Temporary) | Out-Null
try {
    $WindowsRuntimeHashes = @{}
    foreach ($Minecraft in $MinecraftVersions) {
        $Name = "AGMA-Client-Standalone-$Version-mc$Minecraft-fabric-windows-x86_64.jar"
        $Jar = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $Release $Name))
        try {
            $Seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
            foreach ($Entry in $Jar.Entries) {
                $Path = $Entry.FullName.TrimEnd('/')
                if (
                    [string]::IsNullOrEmpty($Path) -or
                    $Path.StartsWith('/') -or
                    $Path.Contains('\') -or
                    $Path.Split('/') -contains '..' -or
                    -not $Seen.Add($Path) -or
                    $Path -match '(?i)(?:^|/)01-standalone-client-development-plan\.md$' -or
                    $Path -match '(?i)(?:^|/)paper-plugin\.yml$' -or
                    $Path -match '(?i)(?:^|/)start-runtime\.(?:sh|ps1|bat|cmd)$'
                ) {
                    throw "Unsafe standalone JAR entry: $($Entry.FullName)"
                }
            }
            $DescriptorBytes = Read-ZipEntryBytes $Jar "META-INF/agma-standalone/runtime-artifact.json"
            $RuntimeBytes = Read-ZipEntryBytes $Jar "META-INF/agma-standalone/runtime.zip"
        } finally {
            $Jar.Dispose()
        }

        $DescriptorText = [System.Text.UTF8Encoding]::new($false, $true).GetString($DescriptorBytes)
        $Descriptor = $DescriptorText | ConvertFrom-Json
        $DescriptorFields = @($Descriptor.PSObject.Properties.Name | Sort-Object)
        $ExpectedFields = @(
            "archive",
            "byteSize",
            "nodeVersion",
            "platform",
            "product",
            "runtimeVersion",
            "schemaVersion",
            "sha256"
        )
        if (
            [string]::Join("`n", $DescriptorFields) -ne [string]::Join("`n", $ExpectedFields) -or
            $Descriptor.schemaVersion -ne 1 -or
            $Descriptor.product -ne "agma-standalone-runtime-artifact" -or
            $Descriptor.platform -ne "windows-x86_64" -or
            $Descriptor.runtimeVersion -ne $RuntimeVersion -or
            $Descriptor.nodeVersion -ne $NodeVersion -or
            $Descriptor.archive -ne "META-INF/agma-standalone/runtime.zip" -or
            $Descriptor.byteSize -ne $RuntimeBytes.Length -or
            $Descriptor.sha256 -ne (Get-Sha256Bytes $RuntimeBytes)
        ) {
            throw "Windows embedded Runtime descriptor is invalid: $Name"
        }
        $WindowsRuntimeHashes[$Minecraft] = Get-Sha256Bytes $RuntimeBytes

        $RuntimeMemory = [System.IO.MemoryStream]::new($RuntimeBytes, $false)
        $RuntimeArchive = [System.IO.Compression.ZipArchive]::new(
            $RuntimeMemory,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        try {
            $RuntimeSeen = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::OrdinalIgnoreCase
            )
            foreach ($Entry in $RuntimeArchive.Entries) {
                $Path = $Entry.FullName.TrimEnd('/')
                $UnixMode = (($Entry.ExternalAttributes -shr 16) -band 0xFFFF)
                if (
                    [string]::IsNullOrEmpty($Path) -or
                    $Path.StartsWith('/') -or
                    $Path.Contains('\') -or
                    $Path.Split('/') -contains '..' -or
                    -not $RuntimeSeen.Add($Path) -or
                    (($UnixMode -band 0xF000) -eq 0xA000) -or
                    $Path -match '(?i)(?:^|/)01-standalone-client-development-plan\.md$' -or
                    $Path -match '(?i)(?:^|/)paper-plugin\.yml$' -or
                    $Path -match '(?i)^protocol/' -or
                    $Path -match '(?i)^capability-packs/'
                ) {
                    throw "Unsafe Windows sidecar entry: $($Entry.FullName)"
                }
            }
            foreach ($Required in @(
                "bin/node.exe",
                "app/dist/standalone/bootstrap/index.js",
                "sidecar-manifest.json"
            )) {
                if (-not $RuntimeSeen.Contains($Required)) {
                    throw "Windows sidecar is missing $Required"
                }
            }
        } finally {
            $RuntimeArchive.Dispose()
            $RuntimeMemory.Dispose()
        }

        $RuntimePath = Join-Path $Temporary "$Minecraft-runtime.zip"
        [System.IO.File]::WriteAllBytes($RuntimePath, $RuntimeBytes)
        $RuntimeRoot = Join-Path $Temporary "$Minecraft-runtime"
        [System.IO.Compression.ZipFile]::ExtractToDirectory($RuntimePath, $RuntimeRoot)
        $EmbeddedNode = Join-Path $RuntimeRoot "bin/node.exe"
        if (-not (Test-Path -LiteralPath $EmbeddedNode -PathType Leaf)) {
            throw "Windows sidecar has no embedded Node executable."
        }
        $ReportedVersion = & $EmbeddedNode --version
        if ($LASTEXITCODE -ne 0 -or $ReportedVersion.Trim() -ne "v$NodeVersion") {
            throw "Embedded Windows Node did not execute with the pinned version."
        }
        $Entrypoint = Join-Path $RuntimeRoot "app/dist/standalone/bootstrap/index.js"
        $PreviousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $BundleOutput = @(& $EmbeddedNode $Entrypoint --invalid 2>&1 | ForEach-Object { $_.ToString() })
            $BundleExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $PreviousPreference
        }
        if (
            $BundleExitCode -eq 0 -or
            [string]::Join("`n", $BundleOutput) -notmatch 'CONFIG_PATH_INVALID'
        ) {
            throw "Embedded Windows standalone Runtime bundle did not execute fail-closed."
        }
    }
    if ($WindowsRuntimeHashes["1.18.2"] -ne $WindowsRuntimeHashes["1.21.11"]) {
        throw "The two Minecraft builds do not embed the same Windows sidecar."
    }
} finally {
    if (Test-Path -LiteralPath $Temporary) {
        Remove-Item -LiteralPath $Temporary -Recurse -Force
    }
}

# The final native invocation is an expected negative probe. Do not leak its exit code to the caller.
Write-Output "verify-standalone-release-windows version=$Version embedded_node=v$NodeVersion result=passed"
exit 0
