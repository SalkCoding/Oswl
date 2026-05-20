# ─────────────────────────────────────────────────────────────────────────────
# OsWL CLI Installer – Windows PowerShell
# Usage (PowerShell as Administrator or scoped to user):
#   iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
#
# After install, authenticate once:
#   oswl auth --username your@email.com --password yourpassword [--server https://your-server]
#
# Then scan your project:
#   cd C:\your\project
#   oswl scan --project <project-name>
# ─────────────────────────────────────────────────────────────────────────────
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$InstallDir = if ($env:OSWL_INSTALL_DIR) { $env:OSWL_INSTALL_DIR } else { "$env:USERPROFILE\.oswl\bin" }
$ConfigDir  = "$env:USERPROFILE\.oswl"
$ScriptPath = "$InstallDir\oswl.ps1"
$ShimPath   = "$InstallDir\oswl.cmd"
$ServerUrl  = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" }

function Write-Info   { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Green }
function Write-Warn   { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Yellow }
function Write-Err    { param($msg) Write-Host "[OsWL] $msg" -ForegroundColor Red; exit 1 }

Write-Info "Starting OsWL CLI installation..."

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path $ConfigDir  | Out-Null

# ── Generate main script (oswl.ps1) ──────────────────────────────────────────
$MainScript = @'
#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$script:DebugMode = $false

$ConfigDir  = "$env:USERPROFILE\.oswl"
$ConfigFile = "$ConfigDir\config.json"

function Write-Dbg { param($msg) if ($script:DebugMode) { Write-Host "[OsWL][DEBUG] $msg" -ForegroundColor Cyan } }

# ── Config ─────────────────────────────────────────────────────────────────
# Stored as JSON: { serverUrl, userId, displayName, projects: [{id,name,apiKey}] }
function Load-Config {
    if (Test-Path $ConfigFile) {
        try { return Get-Content $ConfigFile -Raw | ConvertFrom-Json }
        catch { Write-Host "[OsWL] Warning: corrupt config at $ConfigFile" -ForegroundColor Yellow }
    }
    return $null
}

function Save-Config {
    param([string]$ServerUrl, [long]$UserId, [string]$DisplayName, [array]$Projects)
    if (-not (Test-Path $ConfigDir)) { New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null }
    @{
        serverUrl   = $ServerUrl
        userId      = $UserId
        displayName = $DisplayName
        projects    = $Projects
    } | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigFile -Encoding UTF8
}

# ── auth ──────────────────────────────────────────────────────────────────────
function Invoke-Auth {
    param([string]$Username = "", [string]$Password = "", [string]$Server = "")

    if (-not $Username) { Write-Host "Error: --username <email> is required." -ForegroundColor Red; exit 1 }
    if (-not $Password) { Write-Host "Error: --password <password> is required." -ForegroundColor Red; exit 1 }

    if (-not $Server) {
        $cfg    = Load-Config
        $Server = if ($cfg -and $cfg.serverUrl) { $cfg.serverUrl } else { "http://localhost:8080" }
    }

    $body = @{ username = $Username; password = $Password } | ConvertTo-Json

    try {
        $response = Invoke-RestMethod -Uri "$Server/api/cli/auth" -Method POST `
            -Body $body -ContentType "application/json" -TimeoutSec 15
    } catch {
        $resp       = $null; try { $resp = $_.Exception.Response } catch {}
        $statusCode = if ($resp) { [int]$resp.StatusCode } else { 0 }
        if ($statusCode -eq 401) {
            Write-Host "[OsWL] Error: Invalid username or password." -ForegroundColor Red; exit 1
        }
        Write-Host "[OsWL] Error: Could not reach server at $Server ($($_.Exception.Message))" -ForegroundColor Red; exit 1
    }

    $projects = @($response.projects)
    Save-Config -ServerUrl $Server -UserId ([long]$response.userId) `
                -DisplayName $response.displayName -Projects $projects

    Write-Host "[OsWL] Authenticated as $($response.displayName) ($Username)" -ForegroundColor Green
    Write-Host "       Server: $Server"
    if ($projects.Count -eq 0) {
        Write-Host "[OsWL] No projects found. Create a project first, then re-run 'oswl auth'." -ForegroundColor Yellow
    } else {
        Write-Host "[OsWL] $($projects.Count) project(s) available:"
        foreach ($p in $projects) { Write-Host "         * [$($p.id)] $($p.name)" }
        Write-Host "[OsWL] Run 'oswl scan --project <name>' to scan a project."
    }
}

# ── Dependency collection ───────────────────────────────────────────────────
function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { return (Split-Path (Split-Path $j.Source)) }
    foreach ($reg in @(
        'HKLM:\SOFTWARE\JavaSoft\JDK',
        'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
        'HKLM:\SOFTWARE\Eclipse Adoptium\JDK',
        'HKLM:\SOFTWARE\Microsoft\JDK'
    )) {
        try {
            $cur  = (Get-ItemProperty "$reg" -ErrorAction Stop).CurrentVersion
            $home = (Get-ItemProperty "$reg\$cur" -ErrorAction Stop).JavaHome
            if ($home -and (Test-Path "$home\bin\java.exe")) { return $home }
        } catch {}
    }
    $jdksDir = Join-Path $env:USERPROFILE ".jdks"
    if (Test-Path $jdksDir) {
        $found = Get-ChildItem $jdksDir -Directory | Sort-Object Name -Descending `
            | Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
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

    # ── GRADLE / MAVEN ──────────────────────────────────────────────────────
    $gradleFile = @("build.gradle","build.gradle.kts") `
        | ForEach-Object { Join-Path $ProjectDir $_ } `
        | Where-Object { Test-Path $_ } | Select-Object -First 1

    if ($gradleFile) {
        $wrapperBat = Join-Path $ProjectDir "gradlew.bat"
        $javaHome   = Find-JavaHome
        if ($javaHome) { $env:JAVA_HOME = $javaHome; $env:PATH = "$javaHome\bin;$env:PATH" }
        $ranGradle  = $false
        if ((Test-Path $wrapperBat) -and $javaHome) {
            try {
                Push-Location $ProjectDir
                $tmpOut     = [System.IO.Path]::GetTempFileName()
                $gradleArgs = "dependencies --configuration runtimeClasspath -q"
                Write-Dbg "Running: gradlew $gradleArgs"
                cmd /c "`"$wrapperBat`" $gradleArgs > `"$tmpOut`" 2>&1"
                $lines = Get-Content $tmpOut -ErrorAction SilentlyContinue
                Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue
                Pop-Location

                $projectName  = Split-Path $ProjectDir -Leaf
                $settingsFile = @("settings.gradle","settings.gradle.kts") `
                    | ForEach-Object { Join-Path $ProjectDir $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
                if ($settingsFile) {
                    $sc = Get-Content $settingsFile -Raw -ErrorAction SilentlyContinue
                    if ($sc -and $sc -match "rootProject\.name\s*=\s*[`"']([^`"']+)[`"']") { $projectName = $Matches[1] }
                }

                $depStack = @{}
                $depComps = [ordered]@{}
                foreach ($ln in $lines) {
                    $pos = $ln.IndexOf("+---"); if ($pos -lt 0) { $pos = $ln.IndexOf("\---") }
                    if ($pos -lt 0) { continue }
                    $depth   = [int][Math]::Floor($pos / 5)
                    $suffix  = $ln.Substring($pos + 5).TrimEnd()
                    $suffix  = $suffix -replace '\s*\(\*\)\s*$', ''
                    $resolvedVer = $null
                    if ($suffix -match '\s+->\s+([^\s(]+)') { $resolvedVer = $Matches[1]; $suffix = $suffix -replace '\s+->.*$', '' }
                    $suffix  = $suffix.Trim()
                    $parts   = $suffix -split ':'
                    if ($parts.Count -lt 2) { continue }
                    $compName = "$($parts[0]):$($parts[1])"
                    $compVer  = if ($resolvedVer) { $resolvedVer } elseif ($parts.Count -ge 3) { $parts[2] } else { 'unknown' }
                    $compVer  = $compVer -replace '[\s*()]', ''
                    $depStack[$depth] = @{ N = $compName; V = $compVer }
                    $pathNodes = @([ordered]@{ name = $projectName; version = "local" })
                    for ($lvl = 0; $lvl -lt $depth; $lvl++) {
                        if ($depStack.ContainsKey($lvl)) { $pathNodes += [ordered]@{ name = $depStack[$lvl].N; version = $depStack[$lvl].V } }
                    }
                    $pathNodes += [ordered]@{ name = $compName; version = $compVer }
                    $key = "${compName}:${compVer}"
                    if (-not $depComps.Contains($key)) {
                        $depComps[$key] = [ordered]@{ name=$compName; version=$compVer; ecosystem="MAVEN"; dependencyInfo="Gradle runtimeClasspath"; dependencyPaths=[System.Collections.ArrayList]@() }
                    }
                    [void]$depComps[$key].dependencyPaths.Add($pathNodes)
                }
                foreach ($comp in $depComps.Values) { [void]$c.Add($comp) }
                $ranGradle = $depComps.Count -gt 0
                Write-Dbg "Gradle resolved: $($depComps.Count)"
            } catch { Write-Dbg "Gradle run exception: $_"; try { Pop-Location } catch {} }
        }
        if (-not $ranGradle) {
            Write-Dbg "Falling back to static build.gradle parse"
            $content = Get-Content $gradleFile -Raw
            $seen2   = @{}
            # Configurations: standard JVM, Kotlin (kapt/ksp), Android variants, custom *Implementation.
            $rx      = [regex]'(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|annotationProcessor|kapt|ksp|developmentOnly|(?:androidTest|debug|release)Implementation|[A-Za-z][A-Za-z0-9_]*Implementation)\s*[\(\s][''"]([A-Za-z0-9._\-]+):([A-Za-z0-9._\-]+)(?::([A-Za-z0-9._+\-]+))?[''"]'
            foreach ($m in $rx.Matches($content)) {
                $name = "$($m.Groups[1].Value):$($m.Groups[2].Value)"
                $ver  = if ($m.Groups[3].Success) { $m.Groups[3].Value } else { "unspecified" }
                if (-not $seen2.ContainsKey($name)) { $seen2[$name] = $true; [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="MAVEN"; dependencyInfo="Gradle (declared only)" }) }
            }
            Write-Dbg "Gradle static parsed: $($c.Count)"
        }
    } elseif (Test-Path (Join-Path $ProjectDir "pom.xml")) {
        Write-Dbg "MAVEN: pom.xml"
        try {
            $xml   = [xml](Get-Content (Join-Path $ProjectDir "pom.xml") -Raw)
            $props = @{}
            if ($xml.project.properties) {
                foreach ($p in $xml.project.properties.ChildNodes) { $props[$p.LocalName] = $p.InnerText }
            }
            $parentVer = if ($xml.project.parent -and $xml.project.parent.version) { [string]$xml.project.parent.version } else { "" }
            foreach ($d in $xml.project.dependencies.dependency) {
                if (-not $d) { continue }
                $scope = if ($d.scope) { [string]$d.scope } else { "compile" }
                if ($scope -in @("test","system","provided")) { continue }
                $rawVer = if ($d.version) { [string]$d.version } else { "unspecified" }
                if ($rawVer -match '^\$\{([^}]+)\}$') {
                    $propKey = $Matches[1]
                    $rawVer  = if ($props.ContainsKey($propKey)) { $props[$propKey] } elseif ($propKey -eq "project.parent.version") { $parentVer } else { "unresolved" }
                }
                [void]$c.Add([ordered]@{ name="$($d.groupId):$($d.artifactId)"; version=$rawVer; ecosystem="MAVEN"; dependencyInfo="Maven pom.xml ($scope)" })
            }
            Write-Dbg "Maven pom.xml: $($c.Count)"
        } catch { Write-Dbg "Maven exception: $_" }
    }

    # ── NPM ─────────────────────────────────────────────────────────────────
    $npmBefore = $c.Count
    $npmLock   = Join-Path $ProjectDir "package-lock.json"
    $yarnLock  = Join-Path $ProjectDir "yarn.lock"
    $pnpmLock  = Join-Path $ProjectDir "pnpm-lock.yaml"
    $pkgJson   = Join-Path $ProjectDir "package.json"

    if (Test-Path $npmLock) {
        Write-Dbg "NPM: package-lock.json"
        try {
            $lock = Get-Content $npmLock -Raw | ConvertFrom-Json
            if ($lock.packages) {
                # v3 (npm 7+)
                foreach ($p in $lock.packages.PSObject.Properties) {
                    if ($p.Name -eq "" -or -not $p.Value.version) { continue }
                    $name = $p.Name -replace '^node_modules/', ''
                    [void]$c.Add([ordered]@{
                        name=$name; version=[string]$p.Value.version; ecosystem="NPM"
                        dependencyInfo = if ($p.Value.dev) { "npm devDependency" } else { "npm dependency" }
                    })
                }
            } elseif ($lock.dependencies) {
                # v1/v2 (npm 5-6)
                $queue = [System.Collections.Queue]::new()
                foreach ($entry in $lock.dependencies.PSObject.Properties) { $queue.Enqueue(@{ N=$entry.Name; V=$entry.Value }) }
                while ($queue.Count -gt 0) {
                    $item = $queue.Dequeue()
                    $ver  = if ($item.V.version) { [string]$item.V.version } else { "unknown" }
                    [void]$c.Add([ordered]@{ name=$item.N; version=$ver; ecosystem="NPM"; dependencyInfo="npm dependency" })
                    if ($item.V.dependencies) {
                        foreach ($dep in $item.V.dependencies.PSObject.Properties) { $queue.Enqueue(@{ N=$dep.Name; V=$dep.Value }) }
                    }
                }
            }
        } catch { Write-Dbg "npm lockfile exception: $_" }
    } elseif (Test-Path $yarnLock) {
        Write-Dbg "NPM: yarn.lock"
        try {
            $seen = @{}; $pending = @()
            foreach ($line in (Get-Content $yarnLock)) {
                if ($line -match '^#' -or $line.Trim() -eq '') { continue }
                if ($line -notmatch '^\s' -and ($line -match ':$' -or $line -match '",\s*$')) {
                    $pending = ($line.TrimEnd(':').TrimEnd(',') -split ',') | ForEach-Object {
                        $e = $_.Trim().Trim('"')
                        if ($e -match '^(@[^@]+|[^@"]+)@') { $Matches[1] } else { $null }
                    } | Where-Object { $_ }
                } elseif ($line -match '^\s+version:?\s+"?([0-9][^"\s]+)"?') {
                    $ver = $Matches[1]
                    foreach ($name in $pending) {
                        if ($name -and -not $seen.ContainsKey($name)) {
                            $seen[$name] = $true
                            [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="NPM"; dependencyInfo="yarn.lock" })
                        }
                    }
                    $pending = @()
                }
            }
        } catch { Write-Dbg "yarn.lock exception: $_" }
    } elseif (Test-Path $pnpmLock) {
        Write-Dbg "NPM: pnpm-lock.yaml"
        try {
            $seen = @{}
            foreach ($line in (Get-Content $pnpmLock)) {
                if     ($line -match "^  /?'?(@[^@'\s]+|[^@/'\s]+)@([^:'\s]+)'?:\s*$") { $name=$Matches[1]; $ver=$Matches[2] }
                elseif ($line -match '^  /(@[^/]+/[^/]+)/([0-9][^:_\s]+):')             { $name=$Matches[1]; $ver=$Matches[2] }
                elseif ($line -match '^  /([^@/\s]+)/([0-9][^:_\s]+):')                 { $name=$Matches[1]; $ver=$Matches[2] }
                else { continue }
                if ($name -and -not $seen.ContainsKey($name)) {
                    $seen[$name] = $true
                    [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="NPM"; dependencyInfo="pnpm-lock.yaml" })
                }
            }
        } catch { Write-Dbg "pnpm-lock.yaml exception: $_" }
    } elseif (Test-Path $pkgJson) {
        Write-Dbg "NPM: package.json (no lockfile)"
        try {
            $pkg = Get-Content $pkgJson -Raw | ConvertFrom-Json
            foreach ($type in @("dependencies","devDependencies","peerDependencies","optionalDependencies")) {
                $deps = $pkg.$type; if (-not $deps) { continue }
                $info = if ($type -eq "devDependencies") { "npm devDependency" } elseif ($type -eq "peerDependencies") { "npm peerDependency" } else { "npm dependency" }
                foreach ($prop in $deps.PSObject.Properties) {
                    $ver = [regex]::Replace([string]$prop.Value, '^[\^~>=<! \t]*', '')
                    [void]$c.Add([ordered]@{ name=$prop.Name; version=$ver; ecosystem="NPM"; dependencyInfo=$info })
                }
            }
        } catch { Write-Dbg "package.json exception: $_" }
    }
    Write-Dbg "NPM components: $($c.Count - $npmBefore)"

    # ── PYPI ────────────────────────────────────────────────────────────────
    $pypiBefore  = $c.Count
    $poetryLock  = Join-Path $ProjectDir "poetry.lock"
    $pipfileLock = Join-Path $ProjectDir "Pipfile.lock"
    $uvLock      = Join-Path $ProjectDir "uv.lock"
    $reqFile     = Join-Path $ProjectDir "requirements.txt"

    if (Test-Path $poetryLock) {
        Write-Dbg "PYPI: poetry.lock"
        try {
            foreach ($block in ((Get-Content $poetryLock -Raw) -split '\[\[package\]\]' | Select-Object -Skip 1)) {
                $name = if ($block -match 'name\s*=\s*"([^"]+)"')    { $Matches[1] } else { $null }
                $ver  = if ($block -match 'version\s*=\s*"([^"]+)"') { $Matches[1] } else { $null }
                if ($name -and $ver) { [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="PYPI"; dependencyInfo="poetry.lock" }) }
            }
        } catch { Write-Dbg "poetry.lock exception: $_" }
    } elseif (Test-Path $pipfileLock) {
        Write-Dbg "PYPI: Pipfile.lock"
        try {
            $lock = Get-Content $pipfileLock -Raw | ConvertFrom-Json
            $seenPipfile = @{}
            foreach ($section in @("default","develop")) {
                $deps = $lock.$section; if (-not $deps) { continue }
                $info = if ($section -eq "develop") { "pipenv devDependency" } else { "pipenv dependency" }
                foreach ($prop in $deps.PSObject.Properties) {
                    $ver = ($prop.Value.version -replace '^==', '')
                    if ($ver) {
                        $key = "$($prop.Name):$ver"
                        if (-not $seenPipfile.ContainsKey($key)) {
                            $seenPipfile[$key] = $true
                            [void]$c.Add([ordered]@{ name=$prop.Name; version=$ver; ecosystem="PYPI"; dependencyInfo=$info })
                        }
                    }
                }
            }
        } catch { Write-Dbg "Pipfile.lock exception: $_" }
    } elseif (Test-Path $uvLock) {
        Write-Dbg "PYPI: uv.lock"
        try {
            foreach ($block in ((Get-Content $uvLock -Raw) -split '\[\[package\]\]' | Select-Object -Skip 1)) {
                $name = if ($block -match 'name\s*=\s*"([^"]+)"')    { $Matches[1] } else { $null }
                $ver  = if ($block -match 'version\s*=\s*"([^"]+)"') { $Matches[1] } else { $null }
                if ($name -and $ver) { [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="PYPI"; dependencyInfo="uv.lock" }) }
            }
        } catch { Write-Dbg "uv.lock exception: $_" }
    } elseif (Test-Path $reqFile) {
        Write-Dbg "PYPI: requirements.txt"
        foreach ($line in (Get-Content $reqFile)) {
            $line = $line.Trim(); if ($line -eq "" -or $line.StartsWith("#") -or $line.StartsWith("-")) { continue }
            if ($line -match '^([A-Za-z0-9_.\-\[\]]+)\s*(?:==|===|>=|~=|!=|<=|>|<)\s*([0-9][^\s,;]*)') {
                [void]$c.Add([ordered]@{ name=$Matches[1] -replace '\[.*\]',''; version=$Matches[2]; ecosystem="PYPI"; dependencyInfo="requirements.txt" })
            } elseif ($line -match '^([A-Za-z0-9_.\-\[\]]+)\s*$') {
                [void]$c.Add([ordered]@{ name=$Matches[1] -replace '\[.*\]',''; version="unspecified"; ecosystem="PYPI"; dependencyInfo="requirements.txt" })
            }
        }
    }
    Write-Dbg "PYPI components: $($c.Count - $pypiBefore)"

    # ── GO ──────────────────────────────────────────────────────────────────
    $goBefore = $c.Count
    $goSum    = Join-Path $ProjectDir "go.sum"
    $goMod    = Join-Path $ProjectDir "go.mod"
    if (Test-Path $goSum) {
        Write-Dbg "GO: go.sum"
        try {
            $seen = @{}
            foreach ($line in (Get-Content $goSum)) {
                if ($line -match '^(\S+)\s+(v[^\s/]+)(?:/go\.mod)?\s') {
                    if (-not $seen.ContainsKey($Matches[1])) {
                        $seen[$Matches[1]] = $true
                        [void]$c.Add([ordered]@{ name=$Matches[1]; version=$Matches[2]; ecosystem="GO"; dependencyInfo="go.sum" })
                    }
                }
            }
        } catch { Write-Dbg "go.sum exception: $_" }
    } elseif (Test-Path $goMod) {
        Write-Dbg "GO: go.mod"
        try {
            $inRequire = $false
            foreach ($line in ((Get-Content $goMod -Raw) -split "`n")) {
                $t = $line.Trim()
                if ($t -match '^require\s*\(')  { $inRequire = $true;  continue }
                if ($inRequire -and $t -eq ')') { $inRequire = $false; continue }
                $entry = if ($inRequire) { $t } elseif ($t -match '^require\s+(.+)$') { $Matches[1] } else { $null }
                if ($entry -and $entry -match '^(\S+)\s+(v\S+)') {
                    [void]$c.Add([ordered]@{ name=$Matches[1]; version=$Matches[2]; ecosystem="GO"; dependencyInfo="go.mod" })
                }
            }
        } catch { Write-Dbg "go.mod exception: $_" }
    }
    Write-Dbg "GO components: $($c.Count - $goBefore)"

    # ── CARGO ───────────────────────────────────────────────────────────────
    $cargoBefore = $c.Count
    $cargoLock   = Join-Path $ProjectDir "Cargo.lock"
    if (Test-Path $cargoLock) {
        Write-Dbg "CARGO: Cargo.lock"
        try {
            foreach ($block in ((Get-Content $cargoLock -Raw) -split '\[\[package\]\]' | Select-Object -Skip 1)) {
                $name = if ($block -match 'name\s*=\s*"([^"]+)"')    { $Matches[1] } else { $null }
                $ver  = if ($block -match 'version\s*=\s*"([^"]+)"') { $Matches[1] } else { $null }
                if ($name -and $ver) { [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="CARGO"; dependencyInfo="Cargo.lock" }) }
            }
        } catch { Write-Dbg "Cargo.lock exception: $_" }
    }
    Write-Dbg "CARGO components: $($c.Count - $cargoBefore)"

    # ── NUGET ───────────────────────────────────────────────────────────────
    $nugetBefore = $c.Count
    $nugetLock   = Join-Path $ProjectDir "packages.lock.json"
    $pkgsConfig  = Join-Path $ProjectDir "packages.config"
    $csprojFiles = Get-ChildItem -Path $ProjectDir -Filter "*.csproj" -Recurse -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 20
    if (Test-Path $nugetLock) {
        Write-Dbg "NUGET: packages.lock.json"
        try {
            $lock = Get-Content $nugetLock -Raw | ConvertFrom-Json; $seen = @{}
            foreach ($fw in $lock.dependencies.PSObject.Properties) {
                foreach ($pkg in $fw.Value.PSObject.Properties) {
                    $ver = $pkg.Value.resolved
                    if ($pkg.Name -and $ver -and -not $seen.ContainsKey($pkg.Name)) {
                        $seen[$pkg.Name] = $true
                        [void]$c.Add([ordered]@{ name=$pkg.Name; version=$ver; ecosystem="NUGET"; dependencyInfo="packages.lock.json" })
                    }
                }
            }
        } catch { Write-Dbg "packages.lock.json exception: $_" }
    } elseif ($csprojFiles) {
        Write-Dbg "NUGET: $($csprojFiles.Count) .csproj file(s)"
        $seen = @{}
        foreach ($f in $csprojFiles) {
            try {
                $xml = [xml](Get-Content $f.FullName -Raw)
                foreach ($ref in $xml.SelectNodes("//*[local-name()='PackageReference']")) {
                    $name = $ref.GetAttribute("Include")
                    $ver  = $ref.GetAttribute("Version"); if (-not $ver) { $ver = $ref.GetAttribute("version") }
                    if ($name -and $ver -and -not $seen.ContainsKey($name)) {
                        $seen[$name] = $true
                        [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="NUGET"; dependencyInfo=".csproj" })
                    }
                }
            } catch { Write-Dbg "csproj exception ($($f.Name)): $_" }
        }
    } elseif (Test-Path $pkgsConfig) {
        Write-Dbg "NUGET: packages.config"
        try {
            $xml = [xml](Get-Content $pkgsConfig -Raw)
            foreach ($pkg in $xml.packages.package) {
                if ($pkg.id -and $pkg.version) {
                    [void]$c.Add([ordered]@{ name=$pkg.id; version=$pkg.version; ecosystem="NUGET"; dependencyInfo="packages.config" })
                }
            }
        } catch { Write-Dbg "packages.config exception: $_" }
    }
    Write-Dbg "NUGET components: $($c.Count - $nugetBefore)"

    # ── RUBYGEMS ─────────────────────────────────────────────────────────────
    $rubyBefore = $c.Count
    $gemLock    = Join-Path $ProjectDir "Gemfile.lock"
    if (Test-Path $gemLock) {
        Write-Dbg "RUBYGEMS: Gemfile.lock"
        try {
            $seen = @{}; $inSpecs = $false
            foreach ($line in (Get-Content $gemLock)) {
                if ($line -eq '  specs:') { $inSpecs = $true; continue }
                if ($inSpecs -and $line -notmatch '^ ') { $inSpecs = $false; continue }
                if ($inSpecs -and $line -match '^    ([A-Za-z0-9_-][^\s(]*)\s+\(([0-9][^)]*)\)') {
                    if (-not $seen.ContainsKey($Matches[1])) {
                        $seen[$Matches[1]] = $true
                        [void]$c.Add([ordered]@{ name=$Matches[1]; version=$Matches[2]; ecosystem="RUBYGEMS"; dependencyInfo="Gemfile.lock" })
                    }
                }
            }
        } catch { Write-Dbg "Gemfile.lock exception: $_" }
    }
    Write-Dbg "RUBYGEMS components: $($c.Count - $rubyBefore)"
    Write-Dbg "Total components: $($c.Count)"
    return ,($c.ToArray())
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
    param([string]$ProjectDir = $PWD, [string]$ProjectName = "")

    $cfg = Load-Config
    if (-not $cfg) {
        Write-Host "[OsWL] Not authenticated. Run 'oswl auth --username <email> --password <pass>' first." -ForegroundColor Red; exit 1
    }

    $serverUrl = $cfg.serverUrl
    $projects  = @($cfg.projects)

    if ($projects.Count -eq 0) {
        Write-Host "[OsWL] No projects in config. Run 'oswl auth' again after creating a project." -ForegroundColor Red; exit 1
    }

    $target = $null
    if ($ProjectName) {
        $target = $projects | Where-Object { $_.name -eq $ProjectName -or [string]$_.id -eq $ProjectName } | Select-Object -First 1
        if (-not $target) {
            Write-Host "[OsWL] Error: No project named '$ProjectName' found in config." -ForegroundColor Red
            Write-Host "       Available projects:"
            foreach ($p in $projects) { Write-Host "         * [$($p.id)] $($p.name)" }
            exit 1
        }
    } elseif ($projects.Count -eq 1) {
        $target = $projects[0]
    } else {
        Write-Host "[OsWL] Multiple projects available. Specify one with --project <name>:" -ForegroundColor Yellow
        foreach ($p in $projects) { Write-Host "         * $($p.name)" }
        exit 1
    }

    $apiKey = $target.apiKey
    Write-Host "[OsWL] Scanning project: $($target.name)"

    $version    = Get-ProjectVersion -ProjectDir $ProjectDir
    $components = Get-Components -ProjectDir $ProjectDir

    Write-Host "[OsWL] Found $($components.Count) component(s). (version: $version)"
    Write-Host "[OsWL] Sending to server: $serverUrl"

    $payload = @{ version = $version; components = $components } | ConvertTo-Json -Depth 10

    try {
        $headers  = @{ "Authorization" = "Bearer $apiKey"; "Content-Type" = "application/json" }
        $response = Invoke-RestMethod -Uri "$serverUrl/api/scan" -Method POST `
            -Headers $headers -Body $payload -ContentType "application/json"
        Write-Host "[OsWL] Scan submitted! scanId=$($response.scanId) project=$($target.name)" -ForegroundColor Green
        Write-Host "       View results: $serverUrl/projects/$($target.id)/security-center"
    } catch {
        $resp       = $null; try { $resp = $_.Exception.Response } catch {}
        $statusCode = if ($resp) { [int]$resp.StatusCode.Value__ } else { 0 }
        if ($statusCode -eq 401) {
            Write-Host "[OsWL] Error: API key rejected. Run 'oswl auth' to refresh keys." -ForegroundColor Red; exit 1
        }
        Write-Host "[OsWL] Error: Server responded HTTP $statusCode" -ForegroundColor Red
        if ($null -ne $_.ErrorDetails) { Write-Host $_.ErrorDetails.Message }
        exit 1
    }
}

# ── projects ─────────────────────────────────────────────────────────────────
function Show-Projects {
    $cfg = Load-Config
    if (-not $cfg -or -not $cfg.projects -or @($cfg.projects).Count -eq 0) {
        Write-Host "[OsWL] No projects. Run 'oswl auth' first." -ForegroundColor Yellow; return
    }
    Write-Host "[OsWL] Configured projects (server: $($cfg.serverUrl)):"
    foreach ($p in @($cfg.projects)) {
        Write-Host "  [$($p.id)] $($p.name)"
    }
}

# ── help ─────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Host @"
OsWL CLI - Software Composition Analysis

Usage:
  oswl auth --username <email> --password <pass> [--server <url>]
      Authenticate and cache API keys for all your projects.

  oswl scan [<project_dir>] [--project <name>]
      Scan dependencies and upload results to the server.
      If only one project is configured, --project is optional.

  oswl projects
      List configured projects and their IDs.

  oswl help
      Show this help.

Global flags:
  --debug    Verbose debug output.

Environment:
  OSWL_SERVER_URL   Override server URL (default: http://localhost:8080)
"@
}

# ── Entry point ───────────────────────────────────────────────────────────────
$cmdArgs = [System.Collections.ArrayList]@()
foreach ($a in $args) {
    if ($a -eq "--debug") { $script:DebugMode = $true } else { [void]$cmdArgs.Add($a) }
}
$cmd = if ($cmdArgs.Count -gt 0) { $cmdArgs[0] } else { "help" }

switch ($cmd) {
    "auth" {
        $user = ""; $pass = ""; $server = ""
        for ($i = 1; $i -lt $cmdArgs.Count; $i++) {
            switch ($cmdArgs[$i]) {
                { $_ -in "--username","-u" } { $user   = $cmdArgs[++$i] }
                { $_ -in "--password","-p" } { $pass   = $cmdArgs[++$i] }
                { $_ -in "--server","-s" }   { $server = $cmdArgs[++$i] }
            }
        }
        Invoke-Auth -Username $user -Password $pass -Server $server
    }
    "scan" {
        $dir = $PWD; $proj = ""
        for ($i = 1; $i -lt $cmdArgs.Count; $i++) {
            switch ($cmdArgs[$i]) {
                { $_ -in "--project","-p" } { $proj = $cmdArgs[++$i] }
                default { if (-not $cmdArgs[$i].StartsWith("-")) { $dir = $cmdArgs[$i] } }
            }
        }
        Invoke-Scan -ProjectDir $dir -ProjectName $proj
    }
    "projects"                       { Show-Projects }
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

Write-Info "Installation complete! Authenticate with:"
Write-Info "  oswl auth --username your@email.com --password yourpassword --server $ServerUrl"
