param([Parameter(Mandatory = $true)][string]$RunDirectory)

$ErrorActionPreference = 'Stop'
$hashPath = Join-Path $RunDirectory 'hashes.json'
if (Test-Path -LiteralPath $hashPath) {
    Remove-Item -LiteralPath $hashPath -Force
}
$manifestPath = Join-Path $RunDirectory 'manifest.json'
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$manifest.artifacts = @(Get-ChildItem -LiteralPath $RunDirectory -File |
        Where-Object Name -ne 'hashes.json' | Sort-Object Name | ForEach-Object Name)
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath
$hashes = Get-ChildItem -LiteralPath $RunDirectory -File |
    Sort-Object Name |
    ForEach-Object {
        [ordered]@{
            file = $_.Name
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
@{ schemaVersion = 1; files = @($hashes) } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $hashPath

& (Join-Path $PSScriptRoot 'validate-run-artifacts.ps1') -RunDirectory $RunDirectory
exit $LASTEXITCODE
