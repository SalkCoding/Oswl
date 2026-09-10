param([Parameter(Mandatory = $true)][string]$AssemblyPath)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Resolve-Path -LiteralPath $AssemblyPath)
if ([NuGet.Versioning.NuGetVersion].Assembly.GetName().Version.ToString() -ne '7.9.0.0') {
    throw 'Expected NuGet.Versioning 7.9.0.0'
}
$rows = @(Import-Csv -LiteralPath (Join-Path $PSScriptRoot 'nuget.csv'))
$pairs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$inputs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($row in $rows) {
    if (!$pairs.Add($row.left + ',' + $row.right)) { throw 'Duplicate comparison' }
    [void]$inputs.Add($row.left)
    $left = [NuGet.Versioning.NuGetVersion]::Parse($row.left)
    $right = [NuGet.Versioning.NuGetVersion]::Parse($row.right)
    $actual = [Math]::Sign([NuGet.Versioning.VersionComparer]::VersionRelease.Compare($left, $right))
    if ($actual -ne [int]$row.comparison) { throw "Mismatch: $($row.left), $($row.right)" }
}
if ($inputs.Count -ne 30 -or $pairs.Count -ne 900) { throw 'Incomplete comparison matrix' }
foreach ($left in $inputs) {
    foreach ($right in $inputs) {
        if (!$pairs.Contains($left + ',' + $right)) { throw 'Missing comparison' }
    }
}
Write-Output 'NuGet.Versioning 7.9.0: all 900 comparisons verified'
