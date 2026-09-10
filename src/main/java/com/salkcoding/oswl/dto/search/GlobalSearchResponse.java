package com.salkcoding.oswl.dto.search;

/**
 * Grouped response of the global search endpoint ({@code GET /api/search}).
 * Each group is capped and carries a {@code hasMore} hint instead of a full count.
 */
public record GlobalSearchResponse(
        SearchGroup<ProjectSearchHit> projects,
        SearchGroup<ComponentSearchHit> components,
        SearchGroup<CveSearchHit> cves
) {

    public static GlobalSearchResponse empty() {
        return new GlobalSearchResponse(SearchGroup.empty(), SearchGroup.empty(), SearchGroup.empty());
    }
}
