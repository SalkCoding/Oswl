package com.salkcoding.oswl.dto.search;

/** Component (library) hit for the global search palette, scoped to the project it was found in. */
public record ComponentSearchHit(
        String name,
        String version,
        String ecosystem,
        Long projectId,
        String projectName
) {
}
