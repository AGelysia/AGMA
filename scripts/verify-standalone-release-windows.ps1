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

$Targets = @(
    [PSCustomObject]@{ Minecraft = "1.18.2"; Loader = "fabric" },
    [PSCustomObject]@{ Minecraft = "1.18.2"; Loader = "forge" },
    [PSCustomObject]@{ Minecraft = "1.21.11"; Loader = "fabric" }
)
$Platforms = @("linux-x86_64", "windows-x86_64")
$ChecksumName = "AGMA-Client-Standalone-$Version-SHA256SUMS"
$SbomName = "AGMA-Client-Standalone-$Version-SBOM.cdx.json"
$Expected = [System.Collections.Generic.List[string]]::new()
foreach ($Target in $Targets) {
    foreach ($Platform in $Platforms) {
        $Expected.Add(
            "AGMA-Client-Standalone-$Version-mc$($Target.Minecraft)-$($Target.Loader)-$Platform.jar"
        )
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
if ($Checksums.Count -ne 7) {
    throw "Standalone checksum manifest does not cover exactly six JARs and one SBOM."
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
    @($Sbom.components).Count -lt 11 -or
    @($Sbom.dependencies).Count -lt 12
) {
    throw "Standalone CycloneDX SBOM identity or component inventory is invalid."
}
$ReleaseCountProperty = @(
    $Sbom.metadata.properties |
        Where-Object { $_.name -eq "dev.minecraftagent.agma:releaseArtifactCount" }
)
$JarComponents = @(
    $Sbom.components |
        Where-Object {
            $_.type -eq "application" -and
            $_.name -like "AGMA Client Standalone mc*"
        }
)
if (
    $ReleaseCountProperty.Count -ne 1 -or
    [string]$ReleaseCountProperty[0].value -ne "6" -or
    $JarComponents.Count -ne 6
) {
    throw "Standalone CycloneDX SBOM does not describe exactly six release JAR components."
}
foreach ($Target in $Targets) {
    foreach ($Platform in $Platforms) {
        $Name = "AGMA-Client-Standalone-$Version-mc$($Target.Minecraft)-$($Target.Loader)-$Platform.jar"
        $Components = @(
            $JarComponents |
                Where-Object {
                    @(
                        $_.properties |
                            Where-Object {
                                $_.name -eq "dev.minecraftagent.agma:releaseAsset" -and
                                $_.value -eq $Name
                            }
                    ).Count -eq 1
                }
        )
        if ($Components.Count -ne 1) {
            throw "Standalone CycloneDX SBOM has no unique JAR component for $Name."
        }
        $Component = $Components[0]
        $LoaderProperties = @(
            $Component.properties |
                Where-Object {
                    $_.name -eq "dev.minecraftagent.agma:loader" -and
                    $_.value -eq $Target.Loader
                }
        )
        $MinecraftProperties = @(
            $Component.properties |
                Where-Object {
                    $_.name -eq "dev.minecraftagent.agma:minecraftVersion" -and
                    $_.value -eq $Target.Minecraft
                }
        )
        $PlatformProperties = @(
            $Component.properties |
                Where-Object {
                    $_.name -eq "dev.minecraftagent.agma:platform" -and
                    $_.value -eq $Platform
                }
        )
        $Hashes = @(
            $Component.hashes |
                Where-Object {
                    $_.alg -eq "SHA-256" -and
                    $_.content -eq $Checksums[$Name]
                }
        )
        $ExpectedPurl = "pkg:generic/AGMA-Client-Standalone@${Version}?loader=$($Target.Loader)&minecraft=$($Target.Minecraft)&platform=$Platform"
        if (
            $Component.group -ne "dev.minecraftagent" -or
            $Component.version -ne $Version -or
            $Component.name -ne "AGMA Client Standalone mc$($Target.Minecraft) $($Target.Loader) $Platform" -or
            $Component.purl -ne $ExpectedPurl -or
            $LoaderProperties.Count -ne 1 -or
            $MinecraftProperties.Count -ne 1 -or
            $PlatformProperties.Count -ne 1 -or
            $Hashes.Count -ne 1
        ) {
            throw "Standalone CycloneDX SBOM loader, platform, or hash metadata is invalid for $Name."
        }
    }
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
    foreach ($Target in $Targets) {
        $Minecraft = $Target.Minecraft
        $Loader = $Target.Loader
        $TargetKey = "$Minecraft-$Loader"
        $Name = "AGMA-Client-Standalone-$Version-mc$Minecraft-$Loader-windows-x86_64.jar"
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
            if ($Loader -eq "fabric") {
                if ($Seen.Contains("META-INF/mods.toml") -or $Seen.Contains("META-INF/jarjar/metadata.json")) {
                    throw "Fabric standalone JAR unexpectedly contains Forge metadata: $Name"
                }
                $FabricBytes = Read-ZipEntryBytes $Jar "fabric.mod.json"
            } else {
                if ($Seen.Contains("fabric.mod.json")) {
                    throw "Forge standalone JAR unexpectedly contains Fabric metadata: $Name"
                }
                $ForgeDescriptorBytes = Read-ZipEntryBytes $Jar "META-INF/mods.toml"
                $JarJarBytes = Read-ZipEntryBytes $Jar "META-INF/jarjar/metadata.json"
            }
            $DescriptorBytes = Read-ZipEntryBytes $Jar "META-INF/agma-standalone/runtime-artifact.json"
            $RuntimeBytes = Read-ZipEntryBytes $Jar "META-INF/agma-standalone/runtime.zip"
        } finally {
            $Jar.Dispose()
        }

        if ($Loader -eq "fabric") {
            $FabricText = [System.Text.UTF8Encoding]::new($false, $true).GetString($FabricBytes)
            $Fabric = $FabricText | ConvertFrom-Json
            if (
                $Fabric.id -ne "agma_standalone" -or
                $Fabric.version -ne $Version -or
                $Fabric.depends.minecraft -ne $Minecraft
            ) {
                throw "Fabric identity does not match final JAR name: $Name"
            }
        } else {
            $ForgeDescriptor = [System.Text.UTF8Encoding]::new($false, $true).GetString(
                $ForgeDescriptorBytes
            )
            $ExpectedForgeDescriptor = @"
modLoader = "javafml"
loaderVersion = "[40,)"
license = "Apache-2.0"
clientSideOnly = true
showAsResourcePack = false

[[mods]]
modId = "agma_standalone"
version = "$Version"
displayName = "AGMA Standalone Client"
displayTest = "IGNORE_ALL_VERSION"
authors = "AGMA contributors"
description = '''
Pure client AGMA shell with an authenticated local Runtime boundary.
'''

[[dependencies.agma_standalone]]
modId = "forge"
mandatory = true
versionRange = "[40.2.21,41)"
ordering = "NONE"
side = "CLIENT"

[[dependencies.agma_standalone]]
modId = "minecraft"
mandatory = true
versionRange = "[1.18.2,1.18.3)"
ordering = "NONE"
side = "CLIENT"
"@
            if (
                $ForgeDescriptor.TrimEnd([char[]]"`r`n") -cne
                $ExpectedForgeDescriptor.TrimEnd([char[]]"`r`n")
            ) {
                throw "Forge descriptor identity or client-only boundary is invalid: $Name"
            }
            $JarJarText = [System.Text.UTF8Encoding]::new($false, $true).GetString($JarJarBytes)
            $JarJar = $JarJarText | ConvertFrom-Json
            $JarJarFields = @($JarJar.PSObject.Properties.Name)
            $ExpectedNested = @{
                "core" = "META-INF/jars/AGMA-Standalone-Client-Core-$Version.jar"
                "fabric-common" = "META-INF/jars/AGMA-Standalone-Fabric-Common-$Version.jar"
                "runtime-supervisor-core" = "META-INF/jars/AGMA-Standalone-Runtime-Supervisor-Core-$Version.jar"
            }
            if ($JarJarFields.Count -ne 1 -or $JarJarFields[0] -ne "jars" -or @($JarJar.jars).Count -ne 3) {
                throw "Forge JarJar metadata does not contain exactly three libraries: $Name"
            }
            $NestedArtifacts = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::Ordinal
            )
            $ActualNestedPaths = @(
                $Seen |
                    Where-Object {
                        $_.StartsWith("META-INF/jars/", [System.StringComparison]::OrdinalIgnoreCase) -and
                        $_.EndsWith(".jar", [System.StringComparison]::OrdinalIgnoreCase)
                    }
            )
            if ($ActualNestedPaths.Count -ne 3) {
                throw "Forge JAR does not contain exactly three nested libraries: $Name"
            }
            foreach ($Nested in $JarJar.jars) {
                $Artifact = [string]$Nested.identifier.artifact
                $ExpectedPath = $ExpectedNested[$Artifact]
                $NestedFields = @($Nested.PSObject.Properties.Name | Sort-Object)
                $IdentifierFields = @($Nested.identifier.PSObject.Properties.Name | Sort-Object)
                $VersionFields = @($Nested.version.PSObject.Properties.Name | Sort-Object)
                if (
                    [string]::Join("`n", $NestedFields) -ne "identifier`npath`nversion" -or
                    [string]::Join("`n", $IdentifierFields) -ne "artifact`ngroup" -or
                    [string]::Join("`n", $VersionFields) -ne "artifactVersion`nrange" -or
                    -not $ExpectedNested.ContainsKey($Artifact) -or
                    -not $NestedArtifacts.Add($Artifact) -or
                    $Nested.identifier.group -ne "dev.minecraftagent" -or
                    $Nested.version.range -ne "[$Version,)" -or
                    $Nested.version.artifactVersion -ne $Version -or
                    $Nested.path -ne $ExpectedPath -or
                    -not $Seen.Contains($ExpectedPath)
                ) {
                    throw "Forge JarJar library metadata is invalid: $Name"
                }
            }
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
        $WindowsRuntimeHashes[$TargetKey] = Get-Sha256Bytes $RuntimeBytes

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

        $RuntimePath = Join-Path $Temporary "$TargetKey-runtime.zip"
        [System.IO.File]::WriteAllBytes($RuntimePath, $RuntimeBytes)
        $RuntimeRoot = Join-Path $Temporary "$TargetKey-runtime"
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
    $ReferenceRuntimeHash = $WindowsRuntimeHashes["1.18.2-fabric"]
    foreach ($Target in $Targets) {
        $TargetKey = "$($Target.Minecraft)-$($Target.Loader)"
        if ($WindowsRuntimeHashes[$TargetKey] -ne $ReferenceRuntimeHash) {
            throw "The three release targets do not embed the same Windows sidecar."
        }
    }
} finally {
    if (Test-Path -LiteralPath $Temporary) {
        Remove-Item -LiteralPath $Temporary -Recurse -Force
    }
}

# The final native invocation is an expected negative probe. Do not leak its exit code to the caller.
Write-Output "verify-standalone-release-windows version=$Version targets=3 assets=8 embedded_node=v$NodeVersion result=passed"
exit 0
