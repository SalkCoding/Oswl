-- ROADMAP A8: C/C++ real coverage (vcpkg, git submodule, CMake, Conan CPE matching).
--
-- C/C++ components that are not covered by deps.dev/OSV are enriched through NVD CPE lookups.
-- The best-matching CPE name is stored on the shared Library row so the UI can show the
-- provenance of NVD-derived CVEs and reviewers can spot low-confidence heuristic matches.

ALTER TABLE libraries ADD COLUMN IF NOT EXISTS cpe TEXT;
