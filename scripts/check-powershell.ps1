$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Failed = $false

Get-ChildItem "$Root/scripts/*.ps1", "$Root/packaging/server-separated/*.ps1" |
    Sort-Object FullName |
    ForEach-Object {
    $Tokens = $null
    $Errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $_.FullName,
        [ref]$Tokens,
        [ref]$Errors
    ) | Out-Null
    foreach ($ParseError in $Errors) {
        Write-Error "$($_.FullName):$($ParseError.Extent.StartLineNumber): $($ParseError.Message)" -ErrorAction Continue
        $Failed = $true
    }
}

if ($Failed) {
    throw "PowerShell syntax validation failed"
}

Write-Output "PowerShell syntax validation passed"
