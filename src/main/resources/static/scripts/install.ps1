# ─────────────────────────────────────────────────────────────────────────────
# OsWL CLI Installer – Windows PowerShell
# Usage (PowerShell as Administrator or scoped to user):
#   iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
#
# After install, authenticate once:
#   oswl auth --key <your_api_key> [--server https://your-server]
#
# Then scan your project:
#   cd C:\your\project; oswl scan
# ─────────────────────────────────────────────────────────────────────────────
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$InstallDir   = if ($env:OSWL_INSTALL_DIR) { $env:OSWL_INSTALL_DIR } else { "$env:USERPROFILE\.oswl\bin" }
$ConfigDir    = "$env:USERPROFILE\.oswl"
$ConfigFile   = "$ConfigDir\config.ps1"
$ScriptPath   = "$InstallDir\oswl.ps1"
$ShimPath     = "$InstallDir\oswl.cmd"
$ServerUrl    = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" }

function Write-Info  { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Green }
function Write-Warn  { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Yellow }
function Write-Error2 { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Red; exit 1 }

Write-Info "Starting OsWL CLI installation..."

# ── Create installation directory ───────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $InstallDir  | Out-Null
New-Item -ItemType Directory -Force -Path $ConfigDir   | Out-Null

# ── Generate main script ─────────────────────────────────────────────────────
$MainScript = @'
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ConfigFile = "$env:USERPROFILE\.oswl\config.ps1"

# ── Load / save config ───────────────────────────────────────────────────────
function Load-Config {
    if (Test-Path $ConfigFile) { . $ConfigFile }
}

function Save-Config {
    param([string]$ApiKey, [string]$ServerUrl = "http://localhost:8080")
    @"
`$env:OSWL_API_KEY    = "$ApiKey"
`$env:OSWL_SERVER_URL = "$ServerUrl"
"@ | Set-Content -Path $ConfigFile -Encoding UTF8
    # Restrict file permissions (current user only)
    $acl = Get-Acl $ConfigFile
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $env:USERNAME, "FullControl", "Allow")
    $acl.SetAccessRule($rule)
    Set-Acl $ConfigFile $acl
}

# ── auth command ────────────────────────────────────────────────────────────────
function Invoke-Auth {
    param([string]$Key = "", [string]$Server = "")
    if (-not $Key) { Write-Host "Error: --key <api_key> is required." -ForegroundColor Red; exit 1 }

    Load-Config
    if (-not $Server) { $Server = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" } }

    try {
        $headers = @{ "Authorization" = "Bearer $Key" }
        $response = Invoke-WebRequest -Uri "$Server/api/scan/ping" -Headers $headers -Method GET -UseBasicParsing -TimeoutSec 10
        if ($response.StatusCode -eq 200) {
            Save-Config -ApiKey $Key -ServerUrl $Server
            Write-Host "[OsWL] Authentication successful! Config saved." -ForegroundColor Green
            Write-Host "       Server: $Server"
        }
    } catch {
        $resp = $null; try { $resp = $_.Exception.Response } catch { }
        $statusCode = if ($resp) { [int]$resp.StatusCode } else { 0 }
        if ($statusCode -eq 401) {
            Write-Host "[OsWL] Error: Invalid API key." -ForegroundColor Red; exit 1
        }
        # Save key even if server is unreachable
        Save-Config -ApiKey $Key -ServerUrl $Server
        Write-Host "[OsWL] Warning: Could not reach server. Key saved locally." -ForegroundColor Yellow
    }
}

# ── scan command ────────────────────────────────────────────────────────────────
function Invoke-Scan {
    param([string]$ProjectDir = $PWD)
    Load-Config

    $apiKey    = $env:OSWL_API_KEY
    $serverUrl = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" }

    if (-not $apiKey) {
        Write-Host "[OsWL] Error: API key not set. Run 'oswl auth --key <key>' first." -ForegroundColor Red
        exit 1
    }

    # Auto-detect version (Maven, Gradle, npm)
    $version = "unknown"
    $pomFile   = Join-Path $ProjectDir "pom.xml"
    $gradleFile= Join-Path $ProjectDir "build.gradle"
    $pkgJson   = Join-Path $ProjectDir "package.json"
    if (Test-Path $pomFile) {
        $xml = [xml](Get-Content $pomFile)
        $version = if ($xml.project.version) { [string]$xml.project.version } else { "unknown" }
    } elseif (Test-Path $gradleFile) {
        $line = Select-String -Path $gradleFile -Pattern "^version\s*=" | Select-Object -First 1
        if ($line) { $version = ($line.Line -split "=", 2)[1].Trim().Trim("'").Trim('"') }
    } elseif (Test-Path $pkgJson) {
        $pkg = Get-Content $pkgJson | ConvertFrom-Json
        $version = if ($pkg.version) { $pkg.version } else { "unknown" }
    }

    Write-Host "[OsWL] Scanning dependencies... (version: $version)"

    # Payload (replace with real package manager parsing in production)
    $payload = @{ version = $version; components = @() } | ConvertTo-Json -Depth 10

    Write-Host "[OsWL] Sending to server: $serverUrl"

    try {
        $headers = @{
            "Authorization" = "Bearer $apiKey"
            "Content-Type"  = "application/json"
        }
        $response = Invoke-RestMethod -Uri "$serverUrl/api/scan" -Method POST `
            -Headers $headers -Body $payload -ContentType "application/json"
        Write-Host "[OsWL] Scan complete! scanId=$($response.scanId) status=$($response.status)" -ForegroundColor Green
    } catch {
        $resp = $null; try { $resp = $_.Exception.Response } catch { }
        $statusCode = if ($resp) { [int]$resp.StatusCode.Value__ } else { 0 }
        if ($statusCode -eq 401) {
            Write-Host "[OsWL] Error: API key rejected." -ForegroundColor Red; exit 1
        }
        Write-Host "[OsWL] Error: Server responded HTTP $statusCode" -ForegroundColor Red
        if ($null -ne $_.ErrorDetails) { Write-Host $_.ErrorDetails.Message }
        exit 1
    }
}

# ── Help ──────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Host @"
OsWL CLI - Open-source Software Composition Analysis tool

Usage:
  oswl auth --key <api_key> [--server <url>]   Save API key and verify server connection
  oswl scan [<project_dir>]                    Scan dependencies and upload results to server
  oswl help                                    Show this help message

Environment variables:
  OSWL_API_KEY      API key (can be used instead of config file)
  OSWL_SERVER_URL   Server URL (default: http://localhost:8080)
"@
}

# ── Entry point ───────────────────────────────────────────────────────────────
$cmd = if ($args.Count -gt 0) { $args[0] } else { "help" }
switch ($cmd) {
    "auth" {
        $key = ""; $server = ""
        for ($i = 1; $i -lt $args.Count; $i++) {
            switch ($args[$i]) {
                { $_ -in "--key", "-k" }    { $key    = $args[++$i] }
                { $_ -in "--server", "-s" } { $server = $args[++$i] }
            }
        }
        Invoke-Auth -Key $key -Server $server
    }
    "scan" { Invoke-Scan -ProjectDir (if ($args.Count -gt 1) { $args[1] } else { $PWD }) }
    { $_ -in "help", "--help", "-h" } { Show-Help }
    default { Write-Host "Unknown command: $cmd. Run 'oswl help' for usage." -ForegroundColor Red; exit 1 }
}
'@

$MainScript | Set-Content -Path $ScriptPath -Encoding UTF8

# ── Create CMD shim (Command Prompt compatibility) ──────────────────────────────
@"
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0oswl.ps1" %*
"@ | Set-Content -Path $ShimPath -Encoding ASCII

# ── Register PATH (current user) ─────────────────────────────────────────────
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$userPath;$InstallDir", "User")
    Write-Warn "Added $InstallDir to PATH. Please restart your terminal."
}

Write-Info "Installation complete! Set your API key with:"
Write-Info "  oswl auth --key <your_api_key> --server $ServerUrl"
