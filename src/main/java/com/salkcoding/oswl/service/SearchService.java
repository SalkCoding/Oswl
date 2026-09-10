package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.search.ComponentSearchHit;
import com.salkcoding.oswl.dto.search.CveSearchHit;
import com.salkcoding.oswl.dto.search.GlobalSearchResponse;
import com.salkcoding.oswl.dto.search.ProjectSearchHit;
import com.salkcoding.oswl.dto.search.SearchGroup;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Global search behind the ⌘K palette: projects, components, and CVE ids in one grouped
 * response. Every group is scoped to {@link ProjectAccessService#accessibleProjectIds()}, so
 * a hit can never reference a project the current user cannot view. Components and CVEs are
 * matched inside the latest completed scan of each accessible project.
 *
 * Each group issues a single SQL query capped at {@code GROUP_LIMIT + 1} rows — the extra row
 * only feeds the {@code hasMore} hint, so no COUNT queries are needed.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int GROUP_LIMIT = 8;

    private final ProjectAccessService projectAccessService;
    private final ProjectRepository projectRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final CveRepository cveRepository;

    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String rawQuery) {
        String q = rawQuery != null ? rawQuery.trim() : "";
        if (q.length() < MIN_QUERY_LENGTH) {
            return GlobalSearchResponse.empty();
        }
        List<Long> accessibleIds = projectAccessService.accessibleProjectIds();
        if (accessibleIds.isEmpty()) {
            return GlobalSearchResponse.empty();
        }
        // One extra row per group feeds the hasMore hint without a separate COUNT query.
        Pageable limitPlusOne = PageRequest.of(0, GROUP_LIMIT + 1);
        return new GlobalSearchResponse(
                toGroup(projectRepository.searchByIdInAndName(accessibleIds, q, limitPlusOne),
                        row -> new ProjectSearchHit((Long) row[0], (String) row[1])),
                toGroup(scanComponentRepository.searchAccessibleComponents(accessibleIds, q, limitPlusOne),
                        row -> new ComponentSearchHit((String) row[2], (String) row[3], (String) row[4],
                                (Long) row[0], (String) row[1])),
                toGroup(cveRepository.searchAccessibleCves(accessibleIds, q, limitPlusOne),
                        row -> new CveSearchHit((String) row[0],
                                row[1] instanceof RiskLevel severity ? severity.name() : null,
                                (Long) row[2], (String) row[3])));
    }

    private <T> SearchGroup<T> toGroup(List<Object[]> rows, java.util.function.Function<Object[], T> mapper) {
        boolean hasMore = rows.size() > GROUP_LIMIT;
        List<T> items = rows.stream()
                .limit(GROUP_LIMIT)
                .map(mapper)
                .toList();
        return SearchGroup.of(items, hasMore);
    }
}
