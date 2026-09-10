# Concrete version ordering oracles

## NuGet package-name identity

`verify-nuget-names.ps1 -ClassDirectory build/classes/java/main` compares the compiled
production name canonicalizer with the running .NET `StringComparer.OrdinalIgnoreCase`.
It generates every BMP character accepted by .NET's word-character regex and checks both
directions of the equivalence partition: equivalent native names must share a Java key,
and distinct native names must not share one. Observations and the Java probe are temporary
files deleted by the script. No runtime binary or casing table is bundled.

On 2026-09-11, .NET 10.0.11 on Windows 10.0.26200 produced 50,419 inputs and 49,273
native groups. The implementation matched 50,409 inputs and explicitly rejected 10
characters whose case pairs differ from Java 25's table. These unresolved characters are
U+019B, U+0264, U+1C89, U+1C8A, U+A7CB, U+A7CC, U+A7CD, U+A7DA, U+A7DB, and U+A7DC.
Supplementary characters also remain unsupported by the name matcher. This is evidence
for this runtime, not universal Unicode, provider-search, or cross-platform compatibility.
Parameterized Java regressions additionally cover multi-character names, combining marks,
ASCII/non-ASCII separation, Greek sigma variants, and OSV range/fix/bulk consequences.

The [NuGet identity comparer](https://github.com/NuGet/NuGet.Client/blob/977537e19c6be57fead1411e6cf05f936bf1baf4/src/NuGet.Core/NuGet.Packaging/Core/comparers/PackageIdentityComparer.cs)
uses ordinal case-insensitive name equality; its source carries Apache-2.0 notices.
The [.NET ordinal implementation](https://github.com/dotnet/runtime/blob/v10.0.0/src/libraries/System.Private.CoreLib/src/System/Globalization/Ordinal.cs)
carries MIT notices and selects runtime globalization paths. Only the behavior of installed
runtime APIs is observed here; upstream implementation text and Unicode tables are not copied.

## Concrete versions

`nuget.csv` contains the full cross product of 30 synthetic version strings (900 comparisons).
Generated and independently rechecked on 2026-09-11 with official NuGet.Versioning 7.9.0,
using `NuGetVersion.Parse` and `Math.Sign(VersionComparer.VersionRelease.Compare(left, right))`.
Inputs are the distinct left-column values in first-occurrence order, compared case-sensitively.
Run `verify-nuget.ps1 -AssemblyPath <path-to-net8.0-NuGet.Versioning.dll>` with PowerShell 7
to verify every result and matrix completeness. The DLL is a local verification tool and is
not included in the repository or application.

Package: https://api.nuget.org/v3-flatcontainer/nuget.versioning/7.9.0/nuget.versioning.7.9.0.nupkg
SHA-256: `BA541038E91EB3F26435DC88F0F60A8962A51067B28B15FEF3EE2ABA848D07C8`.
Its nuspec declares Apache-2.0, copyright Microsoft Corporation, and source revision
`977537e19c6be57fead1411e6cf05f936bf1baf4` in https://github.com/NuGet/NuGet.Client.
The repository LICENSE identifies .NET Foundation and Contributors and Apache-2.0:
https://github.com/NuGet/NuGet.Client/blob/977537e19c6be57fead1411e6cf05f936bf1baf4/LICENSE.txt
No upstream implementation or documentation text is copied into these synthetic observations.
These results cover concrete ordering, not vulnerability facts, range syntax, dependency
resolution, or complete NuGet compatibility. `NuGetVersionComparatorTest` checks the Java
comparator against this matrix; OSV ECOSYSTEM ranges and GHSA range/fix comparisons use it.
Enumerated-version identity uses `VersionRelease.Equals` semantics, not Compare=0:
`1.0.0--1` and `1.0.0--01` have equal ordering but distinct identity in the native library.
The OSV evaluator has separate identity regression cases. Other accepted NuGet spellings
and full provider data parity need separate verification.

`pep440.csv` contains 1,444 pairwise comparisons of 38 synthetic version strings.
It was generated on 2026-09-10 with Python 3.14 and pip's vendored `packaging` 26.2:
`(Version(left) > Version(right)) - (Version(left) < Version(right))`.
The ordered set of inputs is the first-occurrence order of the `left` column, so the
cross product and expected results can be regenerated without a random seed.

This covers epoch, zero padding, development/pre/post releases, normalization aliases,
local numeric/text segments and large integers. It is a version-ordering oracle,
not an oracle for dependency specifiers, environment markers or vulnerability facts.

`packaging` is used only as a local verification tool; its code is not copied into
the application or this fixture. Its original LICENSE allows Apache-2.0 or BSD-2-Clause:
https://github.com/pypa/packaging/blob/main/LICENSE
Local LICENSE, LICENSE.APACHE and LICENSE.BSD were inspected. Copyright Donald Stufft
and individual contributors. No Python or packaging runtime dependency is added to OsWL.

The Java comparator implements the version scheme described at:
https://packaging.python.org/en/latest/specifications/version-specifiers/
