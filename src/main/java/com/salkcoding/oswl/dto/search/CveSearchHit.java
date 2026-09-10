package com.salkcoding.oswl.dto.search;

/** CVE hit for the global search palette, scoped to the project it was found in. */
public record CveSearchHit(
        String cveId,
        String severity,
        Long projectId,
        String projectName
) {
}
