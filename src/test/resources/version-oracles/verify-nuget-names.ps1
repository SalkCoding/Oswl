param([Parameter(Mandatory = $true)][string]$ClassDirectory)
$ErrorActionPreference = 'Stop'
$classPath = (Resolve-Path -LiteralPath $ClassDirectory).Path
$oracleFile = [IO.Path]::GetTempFileName()
$probeFile = Join-Path ([IO.Path]::GetTempPath()) ('NuGetNameProbe-' + [Guid]::NewGuid().ToString('N') + '.java')
try {
    $groups = [Collections.Generic.Dictionary[string,int]]::new([StringComparer]::OrdinalIgnoreCase)
    $rows = [Collections.Generic.List[string]]::new()
    for ($code = 0; $code -le 65535; $code++) {
        $name = ([char]$code).ToString()
        if ($name -notmatch '^\w$') { continue }
        if (!$groups.ContainsKey($name)) { $groups.Add($name, $code) }
        $rows.Add("$code,$($groups[$name])")
    }
    [IO.File]::WriteAllLines($oracleFile, $rows)
    [IO.File]::WriteAllText($probeFile, @'
import com.salkcoding.oswl.vdb.AdvisoryPackageNames;
import java.nio.file.*;
import java.util.*;
class NuGetNameProbe {
    public static void main(String[] args) throws Exception {
        var byGroup = new HashMap<Integer, String>();
        var byCanonical = new HashMap<String, Integer>();
        var uncertain = Set.of(0x019b, 0x0264, 0x1c89, 0x1c8a, 0xa7cb, 0xa7cc, 0xa7cd, 0xa7da, 0xa7db, 0xa7dc);
        var observedUncertain = new HashSet<Integer>();
        int verified = 0;
        for (String row : Files.readAllLines(Path.of(args[0]))) {
            String[] parts = row.split(",");
            int code = Integer.parseInt(parts[0]), group = Integer.parseInt(parts[1]);
            String canonical;
            try {
                canonical = AdvisoryPackageNames.canonical("NuGet", Character.toString(code));
            } catch (IllegalArgumentException failure) {
                if (!uncertain.contains(code)) throw new AssertionError("Unexpected unknown: " + code, failure);
                observedUncertain.add(code);
                continue;
            }
            if (uncertain.contains(code)) throw new AssertionError("Runtime-dependent identity was confirmed: " + code);
            String priorCanonical = byGroup.putIfAbsent(group, canonical);
            Integer priorGroup = byCanonical.putIfAbsent(canonical, group);
            if ((priorCanonical != null && !priorCanonical.equals(canonical))
                    || (priorGroup != null && priorGroup != group)) {
                throw new AssertionError("Different equivalence partition at U+" + Integer.toHexString(code));
            }
            verified++;
        }
        if (!observedUncertain.equals(uncertain)) throw new AssertionError("Incomplete uncertainty verification");
        System.out.println("Verified BMP word characters: " + verified + "; explicitly unknown: " + observedUncertain.size());
    }
}
'@)
    & java --class-path $classPath $probeFile $oracleFile
    if ($LASTEXITCODE -ne 0) { throw 'NuGet name equivalence verification failed' }
    Write-Output ".NET $([Environment]::Version), $([Runtime.InteropServices.RuntimeInformation]::OSDescription): $($rows.Count) inputs, $($groups.Count) native identity groups"
} finally {
    Remove-Item -LiteralPath $oracleFile -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $probeFile -ErrorAction SilentlyContinue
}
