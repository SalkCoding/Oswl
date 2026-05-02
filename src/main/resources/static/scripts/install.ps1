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

Write-Info "OsWL CLI 설치를 시작합니다..."

# ── 설치 디렉터리 생성 ──────────────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $InstallDir  | Out-Null
New-Item -ItemType Directory -Force -Path $ConfigDir   | Out-Null

# ── 메인 스크립트 생성 ──────────────────────────────────────────────────────────
$MainScript = @'
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ConfigFile = "$env:USERPROFILE\.oswl\config.ps1"

# ── 설정 로드 / 저장 ────────────────────────────────────────────────────────────
function Load-Config {
    if (Test-Path $ConfigFile) { . $ConfigFile }
}

function Save-Config {
    param([string]$ApiKey, [string]$ServerUrl = "http://localhost:8080")
    @"
`$env:OSWL_API_KEY    = "$ApiKey"
`$env:OSWL_SERVER_URL = "$ServerUrl"
"@ | Set-Content -Path $ConfigFile -Encoding UTF8
    # 파일 권한 제한 (현재 사용자만 읽기/쓰기)
    $acl = Get-Acl $ConfigFile
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $env:USERNAME, "FullControl", "Allow")
    $acl.SetAccessRule($rule)
    Set-Acl $ConfigFile $acl
}

# ── auth 명령 ──────────────────────────────────────────────────────────────────
function Invoke-Auth {
    param([string]$Key = "", [string]$Server = "")
    if (-not $Key) { Write-Host "오류: --key <api_key> 가 필요합니다." -ForegroundColor Red; exit 1 }

    Load-Config
    if (-not $Server) { $Server = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" } }

    try {
        $headers = @{ "Authorization" = "Bearer $Key" }
        $response = Invoke-WebRequest -Uri "$Server/api/scan/ping" -Headers $headers -Method GET -UseBasicParsing -TimeoutSec 10
        if ($response.StatusCode -eq 200) {
            Save-Config -ApiKey $Key -ServerUrl $Server
            Write-Host "[OsWL] 인증 성공! 설정이 저장되었습니다." -ForegroundColor Green
            Write-Host "       서버: $Server"
        }
    } catch {
        if ($_.Exception.Response.StatusCode -eq 401) {
            Write-Host "[OsWL] 오류: API 키가 유효하지 않습니다." -ForegroundColor Red; exit 1
        }
        # 서버 미응답 시에도 키 저장
        Save-Config -ApiKey $Key -ServerUrl $Server
        Write-Host "[OsWL] 경고: 서버에 연결할 수 없습니다. 키는 저장되었습니다." -ForegroundColor Yellow
    }
}

# ── scan 명령 ──────────────────────────────────────────────────────────────────
function Invoke-Scan {
    param([string]$ProjectDir = $PWD)
    Load-Config

    $apiKey    = $env:OSWL_API_KEY
    $serverUrl = if ($env:OSWL_SERVER_URL) { $env:OSWL_SERVER_URL } else { "http://localhost:8080" }

    if (-not $apiKey) {
        Write-Host "[OsWL] 오류: API 키가 설정되지 않았습니다. 'oswl auth --key <key>' 를 실행하세요." -ForegroundColor Red
        exit 1
    }

    # 버전 자동 감지
    $version = "unknown"
    $pomFile   = Join-Path $ProjectDir "pom.xml"
    $gradleFile= Join-Path $ProjectDir "build.gradle"
    $pkgJson   = Join-Path $ProjectDir "package.json"
    if (Test-Path $pomFile) {
        $xml = [xml](Get-Content $pomFile)
        $version = $xml.project.version ?? "unknown"
    } elseif (Test-Path $gradleFile) {
        $line = Select-String -Path $gradleFile -Pattern "^version\s*=" | Select-Object -First 1
        if ($line) { $version = $line.Line -replace "version\s*=\s*['\"]", "" -replace "['\"].*", "" }
    } elseif (Test-Path $pkgJson) {
        $pkg = Get-Content $pkgJson | ConvertFrom-Json
        $version = $pkg.version ?? "unknown"
    }

    Write-Host "[OsWL] 의존성 스캔 중... (버전: $version)"

    # 페이로드 (실제 환경에서는 패키지 매니저 파싱으로 교체)
    $payload = @{ version = $version; components = @() } | ConvertTo-Json -Depth 10

    Write-Host "[OsWL] 서버로 전송 중: $serverUrl"

    try {
        $headers = @{
            "Authorization" = "Bearer $apiKey"
            "Content-Type"  = "application/json"
        }
        $response = Invoke-RestMethod -Uri "$serverUrl/api/scan" -Method POST `
            -Headers $headers -Body $payload -ContentType "application/json"
        Write-Host "[OsWL] 스캔 완료! scanId=$($response.scanId) status=$($response.status)" -ForegroundColor Green
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.Value__
        if ($statusCode -eq 401) {
            Write-Host "[OsWL] 오류: API 키가 거부되었습니다." -ForegroundColor Red; exit 1
        }
        Write-Host "[OsWL] 오류: 서버 응답 HTTP $statusCode" -ForegroundColor Red
        Write-Host $_.ErrorDetails.Message
        exit 1
    }
}

# ── 도움말 ─────────────────────────────────────────────────────────────────────
function Show-Help {
    Write-Host @"
OsWL CLI – 오픈소스 공급망 분석 도구

사용법:
  oswl auth --key <api_key> [--server <url>]   API 키 저장 및 서버 연결 테스트
  oswl scan [<project_dir>]                    의존성 스캔 후 결과를 서버로 전송
  oswl help                                    이 도움말 표시

환경 변수:
  OSWL_API_KEY      API 키 (config 파일 대신 사용 가능)
  OSWL_SERVER_URL   서버 URL (기본값: http://localhost:8080)
"@
}

# ── 진입점 ─────────────────────────────────────────────────────────────────────
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
    default { Write-Host "알 수 없는 명령: $cmd. 'oswl help' 를 실행하세요." -ForegroundColor Red; exit 1 }
}
'@

$MainScript | Set-Content -Path $ScriptPath -Encoding UTF8

# ── CMD 심(shim) 생성 (명령 프롬프트 호환) ────────────────────────────────────
@"
@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0oswl.ps1" %*
"@ | Set-Content -Path $ShimPath -Encoding ASCII

# ── PATH 등록 (현재 사용자) ───────────────────────────────────────────────────
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$userPath;$InstallDir", "User")
    Write-Warn "PATH에 $InstallDir 를 추가했습니다. 터미널을 재시작하세요."
}

Write-Info "설치 완료! 다음 명령으로 API 키를 설정하세요:"
Write-Info "  oswl auth --key <your_api_key> --server $ServerUrl"
