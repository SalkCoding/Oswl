# Advisory test fixture provenance

## GHSA-hh2w-p6rv-4g7w.json

GitHub Advisory Database and contributors; advisory authored by Microsoft and published
by rbhanda in dotnet/runtime. CVE-2024-30105, System.Text.Json (NuGet).

- Source: https://github.com/github/advisory-database/blob/872fc2a7bd18d3a51e648444930929313098ed63/advisories/github-reviewed/2024/07/GHSA-hh2w-p6rv-4g7w/GHSA-hh2w-p6rv-4g7w.json
- Publisher advisory: https://github.com/dotnet/runtime/security/advisories/GHSA-hh2w-p6rv-4g7w
- Retrieved 2026-09-11; record modified 2024-07-10T16:09:24Z.
- SHA-256: `DFC70E63DE40A733BA00AB837C56D56E0776CCD69AB71DFDCCF37B9640212E9C`.
- License: CC-BY-4.0, https://github.com/github/advisory-database/blob/main/LICENSE.md
  and the accompanying `LICENSE-GHSA-CC-BY-4.0.txt`.
- Changes: none; the source JSON, references and supplied Microsoft disclaimer are retained.

The licensed database record is used for reproducible tests. Attribution, source and license
links accompany any redistribution of this fixture. No endorsement is implied. Linked pages
and external content are not copied or licensed by this notice. Test resources are not production
offline database bundles. This fixture does not establish redistribution rights for other sources
or correctness of every NuGet advisory. Publisher and database records are related evidence,
not independent vulnerability discoveries.

## Optional live boundary verification

`OsvNugetLiveVerificationTest` reads OSV only when `OSWL_VERIFY_OSV_NUGET=true`.
The expanded check was executed on 2026-09-13 after strict response parsing was added.
It checks six System.Text.Json versions against two Microsoft advisories:

- [CVE-2024-30105](https://github.com/dotnet/runtime/security/advisories/GHSA-hh2w-p6rv-4g7w): 7.0.0 and 8.0.3 retain the 8.0.4 fix; 8.0.4 no longer matches this advisory.
- [CVE-2024-43485](https://github.com/dotnet/runtime/security/advisories/GHSA-8g4q-xg66-9fp4): 8.0.3 and 8.0.4 retain the 8.0.5 fix; 6.0.9 retains 6.0.10. The fixed boundaries do not match this advisory.

The test asserts these identities and individual fixes without assuming that no other
advisories can exist. Publisher pages were checked directly; their text and the new
live JSON responses are not added as fixtures or included in a distributable bundle.
The existing fixture attribution above remains unchanged. A live check can change or
fail as provider data changes and does not establish offline dataset completeness.
