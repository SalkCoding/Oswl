# OsWL JDK preflight — requires Java 25+ (matches build.gradle toolchain).
$ErrorActionPreference = 'Stop'
$RequiredMajor = 25

function Get-JavaMajor {
    # `java -version` prints to stderr. In Windows PowerShell 5.1 `2>&1` wraps each stderr line
    # in an ErrorRecord, and with $ErrorActionPreference = 'Stop' that surfaces as a terminating
    # NativeCommandError *before* the version string can be read — so a perfectly good JDK was
    # reported as "not installed". Relax the preference for this one call and stringify the
    # records before matching.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $verLine = (& java -version 2>&1 | ForEach-Object { $_.ToString() } | Select-Object -First 1)
        if ($verLine -match '"(\d+)') { return [int]$Matches[1] }
    } catch {
    } finally {
        $ErrorActionPreference = $previous
    }
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
