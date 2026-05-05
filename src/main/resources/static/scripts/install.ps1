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

    # ── MAVEN: Gradle (preferred) or Maven pom.xml ────────────────────────────
    $gradleFile = @("build.gradle", "build.gradle.kts") `
        | ForEach-Object { Join-Path $ProjectDir $_ } `
        | Where-Object { Test-Path $_ } | Select-Object -First 1
    Write-Dbg "Gradle build file: $(if ($gradleFile) { $gradleFile } else { 'not found' })"
    if ($gradleFile) {
        $wrapperBat = Join-Path $ProjectDir "gradlew.bat"
        $javaHome   = Find-JavaHome
        Write-Dbg "Java home: $(if ($javaHome) { $javaHome } else { 'not found' })"
        if ($javaHome) { $env:JAVA_HOME = $javaHome; $env:PATH = "$javaHome\bin;$env:PATH" }
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
                # Determine root project name
                $projectName = Split-Path $ProjectDir -Leaf
                $settingsFile = @("settings.gradle", "settings.gradle.kts") | ForEach-Object { Join-Path $ProjectDir $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
                if ($settingsFile) {
                    $sc = Get-Content $settingsFile -Raw -ErrorAction SilentlyContinue
                    if ($sc -and $sc -match "rootProject\.name\s*=\s*[`"']([^`"']+)[`"']") { $projectName = $Matches[1] }
                }
                # Parse tree: build dependencyPaths for each resolved component
                $depStack = @{}   # depth (int) -> @{ N=name; V=version }
                $depComps = [ordered]@{}  # "name:ver" -> component hashtable
                foreach ($ln in $lines) {
                    $pos = $ln.IndexOf("+---")
                    if ($pos -lt 0) { $pos = $ln.IndexOf("\---") }
                    if ($pos -lt 0) { continue }
                    $depth = [int][Math]::Floor($pos / 5)
                    $suffix = $ln.Substring($pos + 5).TrimEnd()
                    $suffix = $suffix -replace '\s*\(\*\)\s*$', ''
                    $resolvedVer = $null
                    if ($suffix -match '\s+->\s+([^\s(]+)') { $resolvedVer = $Matches[1]; $suffix = $suffix -replace '\s+->.*$', '' }
                    $suffix = $suffix.Trim()
                    $parts = $suffix -split ':'
                    if ($parts.Count -lt 2) { continue }
                    $compName = "$($parts[0]):$($parts[1])"
                    $compVer = if ($resolvedVer) { $resolvedVer } elseif ($parts.Count -ge 3) { $parts[2] } else { 'unknown' }
                    $compVer = $compVer -replace '[\s*()]', ''
                    $depStack[$depth] = @{ N = $compName; V = $compVer }
                    # Build path: [root, ancestors 0..depth-1, current]
                    $pathNodes = @([ordered]@{ name = $projectName; version = $version })
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
            $seen2 = @{}
            $depConfigs = 'implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor|kapt'
            $matches_ = [regex]::Matches($content, "(?:$depConfigs)\s*[\(\s][`"']([A-Za-z0-9._\-]+):([A-Za-z0-9._\-]+)(?::([A-Za-z0-9._\-]+))?[`"']")
            foreach ($m in $matches_) {
                $name = "$($m.Groups[1].Value):$($m.Groups[2].Value)"
                $ver  = if ($m.Groups[3].Success) { $m.Groups[3].Value } else { "unspecified" }
                if (-not $seen2.ContainsKey($name)) {
                    $seen2[$name] = $true
                    [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="MAVEN"; dependencyInfo="Gradle (declared only)" })
                }
            }
            Write-Dbg "Gradle static parsed: $($c.Count)"
        }
    } elseif (Test-Path (Join-Path $ProjectDir "pom.xml")) {
        try {
            $xml = [xml](Get-Content (Join-Path $ProjectDir "pom.xml") -Raw)
            foreach ($d in $xml.project.dependencies.dependency) {
                if (-not $d) { continue }
                [void]$c.Add([ordered]@{
                    name      = "$($d.groupId):$($d.artifactId)"
                    version   = if ($d.version) { [string]$d.version } else { "unspecified" }
                    ecosystem = "MAVEN"; dependencyInfo = "Maven"
                })
            }
            Write-Dbg "Maven parsed: $($c.Count)"
        } catch { Write-Dbg "Maven exception: $_" }
    }

    # ── NPM: package-lock.json > yarn.lock > pnpm-lock.yaml > package.json ────
    $npmBefore = $c.Count
    $npmLock  = Join-Path $ProjectDir "package-lock.json"
    $yarnLock = Join-Path $ProjectDir "yarn.lock"
    $pnpmLock = Join-Path $ProjectDir "pnpm-lock.yaml"
    $pkgJson  = Join-Path $ProjectDir "package.json"
    if (Test-Path $npmLock) {
        Write-Dbg "NPM: package-lock.json"
        try {
            $lock = Get-Content $npmLock -Raw | ConvertFrom-Json
            if ($lock.packages) {
                foreach ($p in $lock.packages.PSObject.Properties) {
                    if ($p.Name -eq "" -or -not $p.Value.version) { continue }
                    [void]$c.Add([ordered]@{
                        name           = ($p.Name -replace '^node_modules/', '')
                        version        = [string]$p.Value.version
                        ecosystem      = "NPM"
                        dependencyInfo = if ($p.Value.dev) { "npm devDependency" } else { "npm dependency" }
                    })
                }
            }
        } catch { Write-Dbg "npm lockfile exception: $_" }
    } elseif (Test-Path $yarnLock) {
        Write-Dbg "NPM: yarn.lock"
        try {
            $seen = @{}; $pending = @()
            foreach ($line in (Get-Content $yarnLock)) {
                if ($line -notmatch '^\s' -and $line -match ':$') {
                    $pending = $line.TrimEnd(':') -split ',\s*' | ForEach-Object {
                        $e = $_.Trim().Trim('"')
                        if ($e -match '^(@[^@]+|[^@]+)@') { $Matches[1] } else { $null }
                    } | Where-Object { $_ }
                } elseif ($line -match '^\s+version:?\s+"?([0-9][^"\s]+)"?') {
                    $ver = $Matches[1]
                    foreach ($name in $pending) {
                        if ($name -and -not $seen.ContainsKey($name)) {
                            $seen[$name] = $true
                            [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="NPM"; dependencyInfo="yarn" })
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
                if     ($line -match '^  /(@[^/]+/[^/]+)/([0-9][^:_\s]+):')            { $name=$Matches[1]; $ver=$Matches[2] }
                elseif ($line -match '^  /([^@/\s]+)/([0-9][^:_\s]+):')                { $name=$Matches[1]; $ver=$Matches[2] }
                elseif ($line -match "^  /?'?(@[^@'\s]+|[^@/'\s]+)@([0-9][^:_'\s]*)'?:\s*$") { $name=$Matches[1]; $ver=$Matches[2] }
                else { continue }
                if (-not $seen.ContainsKey($name)) {
                    $seen[$name] = $true
                    [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="NPM"; dependencyInfo="pnpm" })
                }
            }
        } catch { Write-Dbg "pnpm-lock.yaml exception: $_" }
    } elseif (Test-Path $pkgJson) {
        Write-Dbg "NPM: package.json (fallback)"
        try {
            $pkg = Get-Content $pkgJson -Raw | ConvertFrom-Json
            foreach ($type in @("dependencies", "devDependencies")) {
                $deps = $pkg.$type
                if (-not $deps) { continue }
                $info = if ($type -eq "devDependencies") { "npm devDependency" } else { "npm dependency" }
                foreach ($prop in $deps.PSObject.Properties) {
                    [void]$c.Add([ordered]@{
                        name=$prop.Name; version=($prop.Value -replace '^[\^~>=<]+')
                        ecosystem="NPM"; dependencyInfo=$info
                    })
                }
            }
        } catch { Write-Dbg "package.json exception: $_" }
    }
    Write-Dbg "NPM components added: $($c.Count - $npmBefore)"

    # ── PYPI: poetry.lock > Pipfile.lock > uv.lock > requirements.txt ─────────
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
                if ($name -and $ver) { [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="PYPI"; dependencyInfo="poetry" }) }
            }
        } catch { Write-Dbg "poetry.lock exception: $_" }
    } elseif (Test-Path $pipfileLock) {
        Write-Dbg "PYPI: Pipfile.lock"
        try {
            $lock = Get-Content $pipfileLock -Raw | ConvertFrom-Json
            foreach ($section in @("default", "develop")) {
                $deps = $lock.$section
                if (-not $deps) { continue }
                $info = if ($section -eq "develop") { "pipenv devDependency" } else { "pipenv dependency" }
                foreach ($prop in $deps.PSObject.Properties) {
                    $ver = ($prop.Value.version -replace '^==', '')
                    if ($ver) { [void]$c.Add([ordered]@{ name=$prop.Name; version=$ver; ecosystem="PYPI"; dependencyInfo=$info }) }
                }
            }
        } catch { Write-Dbg "Pipfile.lock exception: $_" }
    } elseif (Test-Path $uvLock) {
        Write-Dbg "PYPI: uv.lock"
        try {
            foreach ($block in ((Get-Content $uvLock -Raw) -split '\[\[package\]\]' | Select-Object -Skip 1)) {
                $name = if ($block -match 'name\s*=\s*"([^"]+)"')    { $Matches[1] } else { $null }
                $ver  = if ($block -match 'version\s*=\s*"([^"]+)"') { $Matches[1] } else { $null }
                if ($name -and $ver) { [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="PYPI"; dependencyInfo="uv" }) }
            }
        } catch { Write-Dbg "uv.lock exception: $_" }
    } elseif (Test-Path $reqFile) {
        Write-Dbg "PYPI: requirements.txt"
        foreach ($line in (Get-Content $reqFile)) {
            $line = $line.Trim()
            if ($line -eq "" -or $line.StartsWith("#")) { continue }
            if ($line -match '^([A-Za-z0-9_.\-]+)==(.+)$') {
                [void]$c.Add([ordered]@{ name=$Matches[1]; version=$Matches[2].Trim(); ecosystem="PYPI"; dependencyInfo="pip" })
            }
        }
    }
    Write-Dbg "PYPI components added: $($c.Count - $pypiBefore)"

    # ── GO: go.sum (preferred, full transitive) or go.mod ────────────────────
    $goBefore = $c.Count
    $goSum = Join-Path $ProjectDir "go.sum"
    $goMod = Join-Path $ProjectDir "go.mod"
    if (Test-Path $goSum) {
        Write-Dbg "GO: go.sum"
        try {
            $seen = @{}
            foreach ($line in (Get-Content $goSum)) {
                if ($line -match '^(\S+)\s+(v[^\s/]+)(?:/go\.mod)?\s') {
                    $name = $Matches[1]; $ver = $Matches[2]
                    if (-not $seen.ContainsKey($name)) {
                        $seen[$name] = $true
                        [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="GO"; dependencyInfo="go.sum" })
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
                if     ($t -match '^require\s*\(')   { $inRequire = $true;  continue }
                elseif ($inRequire -and $t -eq ')')   { $inRequire = $false; continue }
                $entry = if ($inRequire) { $t } elseif ($t -match '^require\s+(.+)$') { $Matches[1] } else { $null }
                if ($entry -and $entry -match '^(\S+)\s+(v\S+)') {
                    [void]$c.Add([ordered]@{ name=$Matches[1]; version=$Matches[2]; ecosystem="GO"; dependencyInfo="go.mod" })
                }
            }
        } catch { Write-Dbg "go.mod exception: $_" }
    }
    Write-Dbg "GO components added: $($c.Count - $goBefore)"

    # ── CARGO: Cargo.lock ─────────────────────────────────────────────────────
    $cargoBefore = $c.Count
    $cargoLock = Join-Path $ProjectDir "Cargo.lock"
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
    Write-Dbg "CARGO components added: $($c.Count - $cargoBefore)"

    # ── NUGET: packages.lock.json > *.csproj > packages.config ───────────────
    $nugetBefore = $c.Count
    $nugetLock   = Join-Path $ProjectDir "packages.lock.json"
    $pkgsConfig  = Join-Path $ProjectDir "packages.config"
    $csprojFiles = Get-ChildItem -Path $ProjectDir -Filter "*.csproj" -Recurse -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 20
    if (Test-Path $nugetLock) {
        Write-Dbg "NUGET: packages.lock.json"
        try {
            $lock = Get-Content $nugetLock -Raw | ConvertFrom-Json
            $seen = @{}
            foreach ($fw in $lock.dependencies.PSObject.Properties) {
                foreach ($pkg in $fw.Value.PSObject.Properties) {
                    $ver = $pkg.Value.resolved
                    if ($pkg.Name -and $ver -and -not $seen.ContainsKey($pkg.Name)) {
                        $seen[$pkg.Name] = $true
                        [void]$c.Add([ordered]@{ name=$pkg.Name; version=$ver; ecosystem="NUGET"; dependencyInfo="NuGet" })
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
                    $ver  = $ref.GetAttribute("Version")
                    if (-not $ver) { $ver = $ref.GetAttribute("version") }
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
    Write-Dbg "NUGET components added: $($c.Count - $nugetBefore)"

    # ── RUBYGEMS: Gemfile.lock ────────────────────────────────────────────────
    $rubyBefore = $c.Count
    $gemLock = Join-Path $ProjectDir "Gemfile.lock"
    if (Test-Path $gemLock) {
        Write-Dbg "RUBYGEMS: Gemfile.lock"
        try {
            $seen = @{}; $inSpecs = $false
            foreach ($line in (Get-Content $gemLock)) {
                if ($line -eq '  specs:') { $inSpecs = $true; continue }
                if ($inSpecs -and $line -notmatch '^ ') { $inSpecs = $false; continue }
                if ($inSpecs -and $line -match '^    ([A-Za-z0-9_-][^\s(]*)\s+\(([0-9][^)]*)\)') {
                    $name = $Matches[1]; $ver = $Matches[2]
                    if (-not $seen.ContainsKey($name)) {
                        $seen[$name] = $true
                        [void]$c.Add([ordered]@{ name=$name; version=$ver; ecosystem="RUBYGEMS"; dependencyInfo="Gemfile.lock" })
                    }
                }
            }
        } catch { Write-Dbg "Gemfile.lock exception: $_" }
    }
    Write-Dbg "RUBYGEMS components added: $($c.Count - $rubyBefore)"

    Write-Dbg "Total components: $($c.Count)"
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

    # Auto-detect version (Maven, Gradle, npm, Cargo, Python)
    $version = "unknown"
    $pomFile    = Join-Path $ProjectDir "pom.xml"
    $gradleFile = Join-Path $ProjectDir "build.gradle"
    $pkgJson    = Join-Path $ProjectDir "package.json"
    $cargoToml  = Join-Path $ProjectDir "Cargo.toml"
    $pyproject  = Join-Path $ProjectDir "pyproject.toml"
    if (Test-Path $pomFile) {
        try { $xml = [xml](Get-Content $pomFile); $version = if ($xml.project.version) { [string]$xml.project.version } else { "unknown" } } catch {}
    } elseif (Test-Path $gradleFile) {
        $line = Select-String -Path $gradleFile -Pattern "^version\s*=" | Select-Object -First 1
        if ($line) { $version = ($line.Line -split "=", 2)[1].Trim().Trim("'").Trim('"') }
    } elseif (Test-Path $pkgJson) {
        try { $pkg = Get-Content $pkgJson | ConvertFrom-Json; $version = if ($pkg.version) { $pkg.version } else { "unknown" } } catch {}
    } elseif (Test-Path $cargoToml) {
        $line = Select-String -Path $cargoToml -Pattern "^version\s*=" | Select-Object -First 1
        if ($line) { $version = ($line.Line -split "=", 2)[1].Trim().Trim('"') }
    } elseif (Test-Path $pyproject) {
        $line = Select-String -Path $pyproject -Pattern "^version\s*=" | Select-Object -First 1
        if ($line) { $version = ($line.Line -split "=", 2)[1].Trim().Trim('"') }
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
        Write-Host "[OsWL] Scan submitted! scanId=$($response.scanId)" -ForegroundColor Green
        Write-Host "       Analysis is running on the server. Check the Security Center for results."
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
