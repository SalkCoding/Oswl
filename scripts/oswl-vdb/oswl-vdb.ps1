# E5: thin wrapper around the oswl-vdb Java CLI (com.salkcoding.oswl.vdb.VdbBuilderCli),
# run on an internet-connected machine to build/verify/inspect offline vulnerability-DB
# bundles for import into an air-gapped OsWL instance.
#
# Usage:
#   scripts\oswl-vdb\oswl-vdb.ps1 build --wanted wanted-list.jsonl --out bundle.zip
#   scripts\oswl-vdb\oswl-vdb.ps1 verify bundle.zip
#   scripts\oswl-vdb\oswl-vdb.ps1 inspect bundle.zip
$ErrorActionPreference = 'Stop'

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')

if ($args.Count -eq 0) {
    Write-Error "Usage: oswl-vdb.ps1 <build|verify|inspect> [args...]"
    exit 1
}

# Gradle's --args takes one whitespace-joined string; individual arguments containing spaces
# (e.g. a Windows path) should be quoted by the caller.
$joined = $args -join ' '
Push-Location $RepoRoot
try {
    & .\gradlew.bat vdbBuild "--args=$joined"
} finally {
    Pop-Location
}
