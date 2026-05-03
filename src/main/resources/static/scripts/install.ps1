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
$ErrorActionPreference = "Stop"
$script:DebugMode = $false

$ConfigFile = "$env:USERPROFILE\.oswl\config.ps1"

function Write-Dbg { param($msg) if ($script:DebugMode) { Write-Host "[OsWL][DEBUG] $msg" -ForegroundColor Cyan } }

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

# ── Dependency collection ───────────────────────────────────────────────────
function Find-JavaHome {
    # 1. Already set
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    # 2. java.exe already in PATH
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { return (Split-Path (Split-Path $j.Source)) }
    # 3. Registry (Oracle/Eclipse/Microsoft JDK)
    foreach ($reg in @(
        'HKLM:\SOFTWARE\JavaSoft\JDK',
        'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
        'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
        'HKLM:\SOFTWARE\Microsoft\JDK'
    )) {
        try {
            $cur = (Get-ItemProperty "$reg" -ErrorAction Stop).CurrentVersion
            $home = (Get-ItemProperty "$reg\$cur" -ErrorAction Stop).JavaHome
            if ($home -and (Test-Path "$home\bin\java.exe")) { return $home }
        } catch {}
    }
    # 4. IntelliJ bundled JDKs (~\.jdks\*)
    $jdksDir = Join-Path $env:USERPROFILE ".jdks"
    if (Test-Path $jdksDir) {
        $found = Get-ChildItem $jdksDir -Directory | Sort-Object Name -Descending `
            | Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    # 5. Common install directories
    foreach ($base in @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\BellSoft",
        "${env:ProgramFiles(x86)}\Java"
    )) {
        if (Test-Path $base) {
            $found = Get-ChildItem $base -Directory | Sort-Object Name -Descending `
                | Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Select-Object -First 1
            if ($found) { return $found.FullName }
        }
    }
    return $null
}

function Get-Components {
    param([string]$ProjectDir)
    [System.Collections.ArrayList]$c = @()

    Write-Dbg "ProjectDir = $ProjectDir"

    # ── Gradle ────────────────────────────────────────────────────────────────
    $gradleFile = @("build.gradle", "build.gradle.kts") `
        | ForEach-Object { Join-Path $ProjectDir $_ } `
        | Where-Object { Test-Path $_ } | Select-Object -First 1
    Write-Dbg "Gradle build file: $(if ($gradleFile) { $gradleFile } else { 'not found' })"
    if ($gradleFile) {
        $wrapperBat = Join-Path $ProjectDir "gradlew.bat"
        Write-Dbg "gradlew.bat present: $(Test-Path $wrapperBat)"
        $javaHome   = Find-JavaHome
        Write-Dbg "Java home: $(if ($javaHome) { $javaHome } else { 'not found' })"
        if ($javaHome) {
            $env:JAVA_HOME = $javaHome
            $env:PATH = "$javaHome\bin;$env:PATH"
        }

        $ranGradle = $false
        if ((Test-Path $wrapperBat) -and $javaHome) {
            try {
                Push-Location $ProjectDir
                $tmpOut = [System.IO.Path]::GetTempFileName()
                $gradleCmd = "`"$wrapperBat`" dependencies --configuration runtimeClasspath -q > `"$tmpOut`" 2>&1"
                Write-Dbg "Running: $gradleCmd"
                cmd /c $gradleCmd
                $lines = Get-Content $tmpOut -ErrorAction SilentlyContinue
                Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue
                Pop-Location
                $lineCount = if ($lines) { @($lines).Count } else { 0 }
                Write-Dbg "Gradle output lines: $lineCount"
                if ($script:DebugMode -and $lineCount -gt 0) {
                    @($lines) | Select-Object -First 5 | ForEach-Object { Write-Host "  [GRADLE] $_" -ForegroundColor DarkCyan }
                }
                $seen = @{}
                foreach ($line in $lines) {
                    if ($line -match '[+\\]---\s+([^: ]+):([^: ]+)(?::([^ (*\r\n]+)|\s+->\s+([^ (*\r\n]+))') {
                        $ver  = if ($Matches[3]) { $Matches[3].Trim() } else { $Matches[4].Trim() }
                        $name = "$($Matches[1]):$($Matches[2])"
                        if (-not $seen.ContainsKey($name)) {
                            $seen[$name] = $true
                            [void]$c.Add([ordered]@{
                                name = $name; version = $ver
                                dependencyInfo = "Gradle runtimeClasspath"
                                patchability = $null; licenseStatus = $null; licenseName = $null; cves = @()
                            })
                        }
                    }
                }
                $ranGradle = $true
                Write-Dbg "Gradle resolved components: $($c.Count)"
            } catch {
                Write-Dbg "Gradle run exception: $_"
                try { Pop-Location } catch {}
            }
        }

        # Fallback: static parse declared deps
        if (-not $ranGradle -or $c.Count -eq 0) {
            Write-Dbg "Falling back to static build.gradle parse"
            $content = Get-Content $gradleFile -Raw
            $seen2 = @{}
            $depConfigs = 'implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor|kapt'
            $matches_ = [regex]::Matches($content,
                "(?:$depConfigs)\s*[\(\s][`"']([A-Za-z0-9._\-]+):([A-Za-z0-9._\-]+)(?::([A-Za-z0-9._\-]+))?[`"']")
            foreach ($m in $matches_) {
                $name = "$($m.Groups[1].Value):$($m.Groups[2].Value)"
                $ver  = if ($m.Groups[3].Success) { $m.Groups[3].Value } else { "unspecified" }
                if (-not $seen2.ContainsKey($name)) {
                    $seen2[$name] = $true
                    [void]$c.Add([ordered]@{
                        name = $name; version = $ver
                        dependencyInfo = "Gradle (declared only)"
                        patchability = $null; licenseStatus = $null; licenseName = $null; cves = @()
                    })
                }
            }
            Write-Dbg "Static parsed components: $($c.Count)"
        }
    }

    # ── Maven ─────────────────────────────────────────────────────────────────
    $hasMaven = $c.Count -eq 0 -and (Test-Path (Join-Path $ProjectDir "pom.xml"))
    Write-Dbg "Maven fallback: $hasMaven"
    if ($hasMaven) {
        try {
            $xml  = [xml](Get-Content (Join-Path $ProjectDir "pom.xml") -Raw)
            $deps = $xml.project.dependencies.dependency
            foreach ($d in $deps) {
                if (-not $d) { continue }
                [void]$c.Add([ordered]@{
                    name = "$($d.groupId):$($d.artifactId)"
                    version = if ($d.version) { [string]$d.version } else { "unspecified" }
                    dependencyInfo = "Maven"
                    patchability = $null; licenseStatus = $null; licenseName = $null; cves = @()
                })
            }
            Write-Dbg "Maven parsed components: $($c.Count)"
        } catch { Write-Dbg "Maven exception: $_" }
    }

    # ── npm ───────────────────────────────────────────────────────────────────
    $lockFile = Join-Path $ProjectDir "package-lock.json"
    $hasNpm = $c.Count -eq 0 -and (Test-Path $lockFile)
    Write-Dbg "npm fallback: $hasNpm"
    if ($hasNpm) {
        try {
            $lock = Get-Content $lockFile -Raw | ConvertFrom-Json
            if ($lock.packages) {
                foreach ($p in $lock.packages.PSObject.Properties) {
                    if ($p.Name -eq "" -or -not $p.Value.version) { continue }
                    [void]$c.Add([ordered]@{
                        name = ($p.Name -replace '^node_modules/', '')
                        version = [string]$p.Value.version
                        dependencyInfo = if ($p.Value.dev) { "npm devDependency" } else { "npm dependency" }
                        patchability = $null; licenseStatus = $null
                        licenseName = if ($p.Value.license) { [string]$p.Value.license } else { $null }
                        cves = @()
                    })
                }
            }
            Write-Dbg "npm parsed components: $($c.Count)"
        } catch { Write-Dbg "npm exception: $_" }
    }

    # ── pip ───────────────────────────────────────────────────────────────────
    $reqFile = Join-Path $ProjectDir "requirements.txt"
    $hasPip = $c.Count -eq 0 -and (Test-Path $reqFile)
    Write-Dbg "pip fallback: $hasPip"
    if ($hasPip) {
        foreach ($line in (Get-Content $reqFile)) {
            $line = $line.Trim()
            if ($line -eq "" -or $line.StartsWith("#")) { continue }
            if ($line -match '^([A-Za-z0-9_.\-]+)==(.+)$') {
                [void]$c.Add([ordered]@{
                    name = $Matches[1]; version = $Matches[2].Trim()
                    dependencyInfo = "pip"
                    patchability = $null; licenseStatus = $null; licenseName = $null; cves = @()
                })
            }
        }
        Write-Dbg "pip parsed components: $($c.Count)"
    }

    return ,($c.ToArray())
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

    $components = Get-Components -ProjectDir $ProjectDir
    Write-Host "[OsWL] Found $($components.Count) component(s)."

    $payload = @{ version = $version; components = $components } | ConvertTo-Json -Depth 10

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

Global flags:
  --debug    Print detailed debug output (dependency resolution steps, Gradle output, etc.)

Environment variables:
  OSWL_API_KEY      API key (can be used instead of config file)
  OSWL_SERVER_URL   Server URL (default: http://localhost:8080)
"@
}

# ── Entry point ───────────────────────────────────────────────────────────────
# Pre-process global flags (e.g. --debug) before dispatching commands
$cmdArgs = [System.Collections.ArrayList]@()
foreach ($a in $args) {
    if ($a -eq "--debug") { $script:DebugMode = $true }
    else { [void]$cmdArgs.Add($a) }
}
$cmd = if ($cmdArgs.Count -gt 0) { $cmdArgs[0] } else { "help" }
switch ($cmd) {
    "auth" {
        $key = ""; $server = ""
        for ($i = 1; $i -lt $cmdArgs.Count; $i++) {
            switch ($cmdArgs[$i]) {
                { $_ -in "--key", "-k" }    { $key    = $cmdArgs[++$i] }
                { $_ -in "--server", "-s" } { $server = $cmdArgs[++$i] }
            }
        }
        Invoke-Auth -Key $key -Server $server
    }
    "scan" { $dir = if ($cmdArgs.Count -gt 1) { $cmdArgs[1] } else { $PWD }; Invoke-Scan -ProjectDir $dir }
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
