# ─────────────────────────────────────────────────────────────────────────────
# OsWL CLI Installer – Windows PowerShell
# Usage (PowerShell as Administrator or scoped to user):
#   iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
#
# After install, scan your project:
#   cd C:\your\project
#   oswl scan --key <api_key> --server https://your-server
#
# Optional user attribution:
#   oswl scan --key <api_key> -u user@example.com -p secret
# ─────────────────────────────────────────────────────────────────────────────
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$InstallDir = if ($env:OSWL_INSTALL_DIR) { $env:OSWL_INSTALL_DIR } else { "$env:USERPROFILE\.oswl\bin" }
$ScriptPath = "$InstallDir\oswl.ps1"
$ShimPath   = "$InstallDir\oswl.cmd"
$ServerUrl  = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" }

function Write-Info   { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Green }
function Write-Warn   { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Yellow }
function Write-Err    { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Red; exit 1 }

Write-Info "Starting OsWL CLI installation..."

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

# ── Generate main script (oswl.ps1) ──────────────────────────────────────────
$MainScript = @'
#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$script:DebugMode = $false

function Write-Dbg { param($msg) if ($script:DebugMode) { Write-Host "[OsWL][DEBUG] $msg" -ForegroundColor Cyan } }

# ── Manifest packaging (rules from manifest-rules.json) ───────────────────────
$script:ManifestRulesFile = Join-Path $env:USERPROFILE '.oswl\manifest-rules.json'

function Ensure-ManifestRules {
    param([string]$Server)
    $dir = Split-Path $script:ManifestRulesFile -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $url = "$Server/scripts/manifest-rules.json"
    try {
        Invoke-WebRequest -Uri $url -OutFile "$script:ManifestRulesFile.tmp" -UseBasicParsing | Out-Null
        Move-Item -Force "$script:ManifestRulesFile.tmp" $script:ManifestRulesFile
        Write-Dbg "Updated manifest rules from $url"
    } catch {
        if (-not (Test-Path $script:ManifestRulesFile)) {
            throw "[OsWL] manifest-rules.json not found. Download from $url"
        }
    }
    $script:ManifestRules = Get-Content $script:ManifestRulesFile -Raw | ConvertFrom-Json
}

function Test-ManifestSkipPath {
    param([string]$RelPath)
    foreach ($d in $script:ManifestRules.skipDirs) {
        if ($RelPath -match "(\\|/)$([regex]::Escape($d))(\\|/|`$)") { return $true }
    }
    return $false
}

function Test-ManifestIncluded {
    param([string]$RelPath)
    $base = [System.IO.Path]::GetFileName($RelPath)
    $norm = $RelPath.Replace('\','/')
    if ($script:ManifestRules.exactFileNames -contains $base) { return $true }
    foreach ($s in $script:ManifestRules.fileSuffixes) {
        if ($base.EndsWith($s)) { return $true }
    }
    foreach ($p in $script:ManifestRules.pathPrefixes) {
        if ($norm.StartsWith($p)) { return $true }
    }
    if ($norm.StartsWith('buildSrc/')) {
        foreach ($s in $script:ManifestRules.buildSrcSuffixes) {
            if ($base.EndsWith($s)) { return $true }
        }
    }
    return $false
}

function Pack-ManifestArchive {
    param([string]$ProjectDir, [string]$Server)
    Ensure-ManifestRules -Server $Server
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipPath = Join-Path ([IO.Path]::GetTempPath()) ("oswl-manifests-" + [guid]::NewGuid().ToString('N') + '.zip')
    $files = @(Get-ChildItem -Path $ProjectDir -Recurse -File | Where-Object {
        $rel = $_.FullName.Substring($ProjectDir.Length).TrimStart('\','/').Replace('\','/')
        if (Test-ManifestSkipPath $rel) { return $false }
        if (Test-ManifestIncluded $rel) { Write-Dbg "manifest: $rel"; return $true }
        return $false
    })
    if ($files.Count -eq 0) { throw "[OsWL] No manifest files found under $ProjectDir" }
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in $files) {
            $rel = $f.FullName.Substring($ProjectDir.Length).TrimStart('\','/').Replace('\','/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel)
        }
    } finally { $zip.Dispose() }
    return $zipPath
}

function Invoke-ParseManifests {
    param([string]$ZipPath, [string]$Server, [string]$ApiKey)
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
        throw '[OsWL] curl.exe is required for manifest upload.'
    }
    $output = & curl.exe -s -w "`n%{http_code}" -X POST `
        -H "Authorization: Bearer $ApiKey" `
        -F "archive=@$ZipPath" `
        "$Server/api/scan/parse"
    $lines = $output -split "`n"
    $code = $lines[-1]
    $body = ($lines[0..([Math]::Max(0, $lines.Length - 2))] -join "`n")
    if ($code -ne '200') { throw "[OsWL] Parse failed HTTP $code`n$body" }
    return $body | ConvertFrom-Json
}
function Get-ProjectVersion {
    param([string]$ProjectDir)
    $pomFile   = Join-Path $ProjectDir "pom.xml"
    $gradleKts = Join-Path $ProjectDir "build.gradle.kts"
    $gradleFil = Join-Path $ProjectDir "build.gradle"
    $pkgJson   = Join-Path $ProjectDir "package.json"
    $cargoToml = Join-Path $ProjectDir "Cargo.toml"
    $pyproject = Join-Path $ProjectDir "pyproject.toml"
    if (Test-Path $pomFile) {
        try { $xml = [xml](Get-Content $pomFile); return if ($xml.project.version) { [string]$xml.project.version } else { "unknown" } } catch {}
    }
    foreach ($gf in @($gradleKts, $gradleFil)) {
        if (Test-Path $gf) {
            $line = Select-String -Path $gf -Pattern 'version\s*=\s*[''"]([^''"]+)[''"]' | Select-Object -First 1
            if ($line -and $line.Matches.Count -gt 0 -and $line.Matches[0].Groups.Count -gt 1) { return $line.Matches[0].Groups[1].Value }
            $line2 = Select-String -Path $gf -Pattern '^version\s*=\s*' | Select-Object -First 1
            if ($line2) { return ($line2.Line -split '=',2)[1].Trim().Trim("'").Trim('"') }
        }
    }
    if (Test-Path $pkgJson) {
        try { $pkg = Get-Content $pkgJson | ConvertFrom-Json; if ($pkg.version) { return $pkg.version } } catch {}
    }
    if (Test-Path $cargoToml) {
        $line = Select-String -Path $cargoToml -Pattern '^version\s*=\s*"([^"]+)"' | Select-Object -First 1
        if ($line) { return ($line.Line -split '"',3)[1] }
    }
    if (Test-Path $pyproject) {
        $line = Select-String -Path $pyproject -Pattern '^version\s*=\s*"([^"]+)"' | Select-Object -First 1
        if ($line) { return ($line.Line -split '"',3)[1] }
    }
    return "unknown"
}

# ── scan ─────────────────────────────────────────────────────────────────────
function Invoke-Scan {
    param(
        [string]$ProjectDir = $PWD,
        [string]$ApiKey     = "",
        [string]$Username   = "",
        [string]$Password   = "",
        [string]$Server     = ""
    )

    # Fall back to environment variables
    if (-not $ApiKey)   { $ApiKey   = if ($env:OSWL_API_KEY)    { $env:OSWL_API_KEY }    else { "" } }
    if (-not $Username) { $Username = if ($env:OSWL_USERNAME)   { $env:OSWL_USERNAME }   else { "" } }
    if (-not $Password) { $Password = if ($env:OSWL_PASSWORD)   { $env:OSWL_PASSWORD }   else { "" } }
    if (-not $Server)   { $Server   = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" } }

    if (-not $ApiKey) {
        Write-Host "[OsWL] Error: --key <api_key> is required (or set OSWL_API_KEY)." -ForegroundColor Red; exit 1
    }
    if (-not $Username) {
        Write-Host "[OsWL] Error: -u <email> is required (or set OSWL_USERNAME)." -ForegroundColor Red; exit 1
    }

    # Prompt for password interactively if not provided on the command line
    if (-not $Password) {
        $securePass = Read-Host "Password for $Username" -AsSecureString
        $bstr       = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePass)
        $Password   = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }

    $version = Get-ProjectVersion -ProjectDir $ProjectDir
    $zipPath = Pack-ManifestArchive -ProjectDir $ProjectDir -Server $Server
    try {
        Write-Host "[OsWL] Parsing manifests on server..."
        $parsed = Invoke-ParseManifests -ZipPath $zipPath -Server $Server -ApiKey $ApiKey
    } finally {
        Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    }

    Write-Host "[OsWL] Found $($parsed.componentCount) component(s). (version: $version)"
    Write-Host "[OsWL] Sending to server: $Server"

    $payloadMap = [ordered]@{ version = $version; components = $parsed.components }
    $payloadMap["submitterEmail"]    = $Username
    $payloadMap["submitterPassword"] = $Password
    $payload = $payloadMap | ConvertTo-Json -Depth 10

    try {
        $headers  = @{ "Authorization" = "Bearer $ApiKey"; "Content-Type" = "application/json" }
        $response = Invoke-RestMethod -Uri "$Server/api/scan" -Method POST `
            -Headers $headers -Body $payload -ContentType "application/json"
        Write-Host "[OsWL] Scan submitted! scanId=$($response.scanId)" -ForegroundColor Green
        Write-Host "       View results at: $Server"
    } catch {
        $resp       = $null; try { $resp = $_.Exception.Response } catch {}
        $statusCode = if ($resp) { [int]$resp.StatusCode.Value__ } else { 0 }
        switch ($statusCode) {
            401     { Write-Host "[OsWL] Error: API key rejected or invalid credentials." -ForegroundColor Red }
            403     { Write-Host "[OsWL] Error: User does not have SCAN_SUBMIT permission." -ForegroundColor Red }
            default { Write-Host "[OsWL] Error: Server responded HTTP $statusCode" -ForegroundColor Red }
        }
        if ($null -ne $_.ErrorDetails) { Write-Host $_.ErrorDetails.Message }
        exit 1
    }
}

# ── help ─────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Host @"
OsWL CLI - Software Composition Analysis

Usage:
  oswl scan [<project_dir>]
              --key|-k    <api_key>       API key linked to the target project
                                           (or env OSWL_API_KEY)
              -u|--username <email>       Your OsWL account email (required)
                                           (or env OSWL_USERNAME)
              -p|--password <password>    Your OsWL account password (required; prompted if omitted)
                                           (or env OSWL_PASSWORD)
              --server|-s <url>           OsWL server URL
                                           (or env OSWL_SERVER_URL, default: http://localhost:8080)

  oswl help
      Show this help.

Examples:
  # minimal
  oswl scan --key oswl_abc123 -u dev@company.com

  # full (password on command line — not recommended for interactive shells)
  oswl scan -k oswl_abc123 -u dev@company.com -p secret --server https://sca.company.com

  # CI/CD via env vars (credentials not in shell history)
  `$env:OSWL_API_KEY    = 'oswl_abc123'
  `$env:OSWL_USERNAME   = 'ci@company.com'
  `$env:OSWL_PASSWORD   = 'secret'
  `$env:OSWL_SERVER_URL = 'https://sca.company.com'
  oswl scan

Notes:
  - Credentials are authenticated server-side before scan data is accepted.
  - The password is NEVER stored on disk and NEVER included in audit logs.
  - For CI/CD, use env vars so credentials do not appear in shell history.

Global flags:
  --debug    Verbose debug output.
"@
}

# ── Entry point ───────────────────────────────────────────────────────────────
$cmdArgs = [System.Collections.ArrayList]@()
foreach ($a in $args) {
    if ($a -eq "--debug") { $script:DebugMode = $true } else { [void]$cmdArgs.Add($a) }
}
$cmd = if ($cmdArgs.Count -gt 0) { $cmdArgs[0] } else { "help" }

switch ($cmd) {
    "scan" {
        $key = ""; $user = ""; $pass = ""; $server = ""; $dir = $PWD
        for ($i = 1; $i -lt $cmdArgs.Count; $i++) {
            switch ($cmdArgs[$i]) {
                { $_ -in "--key","-k" }          { $key    = $cmdArgs[++$i] }
                { $_ -in "--username","-u" }     { $user   = $cmdArgs[++$i] }
                { $_ -in "--password","-p" }     { $pass   = $cmdArgs[++$i] }
                { $_ -in "--server","-s" }       { $server = $cmdArgs[++$i] }
                default { if (-not $cmdArgs[$i].StartsWith("-")) { $dir = $cmdArgs[$i] } }
            }
        }
        Invoke-Scan -ProjectDir $dir -ApiKey $key -Username $user -Password $pass -Server $server
    }
    { $_ -in "help","--help","-h" }  { Show-Help }
    default {
        Write-Host "Unknown command: $cmd. Run 'oswl help' for usage." -ForegroundColor Red; exit 1
    }
}
'@

$MainScript | Set-Content -Path $ScriptPath -Encoding UTF8

# ── CMD shim ─────────────────────────────────────────────────────────────────
@"
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0oswl.ps1" %*
"@ | Set-Content -Path $ShimPath -Encoding ASCII

# ── Register PATH ─────────────────────────────────────────────────────────────
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$userPath;$InstallDir", "User")
    Write-Warn "Added $InstallDir to PATH. Please restart your terminal."
}

Write-Info "Installation complete! Scan a project with:"
Write-Info "  oswl scan --key <your_api_key> --server $ServerUrl"
