# OsWL JDK preflight — requires Java 25+ (matches build.gradle toolchain).
$ErrorActionPreference = 'Stop'
$RequiredMajor = 25

function Get-JavaMajor {
    try {
        $verLine = (java -version 2>&1 | Select-Object -First 1) -as [string]
        if ($verLine -match '"(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Show-InstallHints {
    Write-Host ""
    Write-Host "OsWL requires JDK $RequiredMajor or later." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Install options:"
    Write-Host "  Windows:  winget install Microsoft.OpenJDK.$RequiredMajor"
    Write-Host "            or download from https://adoptium.net/"
    Write-Host ""
    Write-Host "Then verify:  java -version"
    Write-Host "Start OsWL:   .\gradlew.bat bootRun"
}

$major = Get-JavaMajor
if ($major -eq 0) {
    Write-Host "[OsWL] Java (JDK) is not installed or not on PATH." -ForegroundColor Red
    Show-InstallHints
    exit 1
}
if ($major -lt $RequiredMajor) {
    Write-Host "[OsWL] Java $major found, but JDK ${RequiredMajor}+ is required." -ForegroundColor Red
    Show-InstallHints
    exit 1
}

Write-Host "[OsWL] JDK OK (Java $major)." -ForegroundColor Green
