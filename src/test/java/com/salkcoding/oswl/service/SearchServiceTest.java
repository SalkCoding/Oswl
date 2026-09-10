package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.search.GlobalSearchResponse;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService unit tests")
class SearchServiceTest {

    @Mock ProjectAccessService projectAccessService;
    @Mock ProjectRepository projectRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock CveRepository cveRepository;

    @InjectMocks SearchService searchService;

    @Test
    @DisplayName("query shorter than 2 chars after trim returns empty groups without touching repos")
    void shortQuery_shortCircuits() {
        for (String q : new String[]{null, "", " ", "a", " a "}) {
            GlobalSearchResponse response = searchService.search(q);

            assertThat(response.projects().items()).isEmpty();
            assertThat(response.components().items()).isEmpty();
            assertThat(response.cves().items()).isEmpty();
            assertThat(response.projects().hasMore()).isFalse();
        }
        verifyNoInteractions(projectAccessService, projectRepository, scanComponentRepository, cveRepository);
    }

    @Test
    @DisplayName("user with no accessible projects gets empty groups and repos are never queried")
    void noAccessibleProjects_returnsEmpty() {
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of());

        GlobalSearchResponse response = searchService.search("log4j");

        assertThat(response.projects().items()).isEmpty();
        assertThat(response.components().items()).isEmpty();
        assertThat(response.cves().items()).isEmpty();
        verifyNoInteractions(projectRepository, scanComponentRepository, cveRepository);
    }

    @Test
    @DisplayName("groups are capped at the limit and hasMore is set from the extra row")
    void groupsAreCapped_withHasMoreHint() {
        List<Long> accessible = List.of(1L, 2L);
        when(projectAccessService.accessibleProjectIds()).thenReturn(accessible);

        List<Object[]> projectRows = new ArrayList<>();
        for (long i = 1; i <= SearchService.GROUP_LIMIT + 1; i++) {
            projectRows.add(new Object[]{i, "project-" + i});
        }
        when(projectRepository.searchByIdInAndName(eq(accessible), eq("proj"), any(Pageable.class)))
                .thenReturn(projectRows);
        when(scanComponentRepository.searchAccessibleComponents(eq(accessible), eq("proj"), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[]{1L, "project-1", "proj-lib", "1.0.0", "MAVEN"}));
        when(cveRepository.searchAccessibleCves(eq(accessible), eq("proj"), any(Pageable.class)))
                .thenReturn(List.of());

        GlobalSearchResponse response = searchService.search(" proj ");

        assertThat(response.projects().items()).hasSize(SearchService.GROUP_LIMIT);
        assertThat(response.projects().hasMore()).isTrue();
        assertThat(response.projects().items().get(0).name()).isEqualTo("project-1");

        assertThat(response.components().items()).hasSize(1);
        assertThat(response.components().hasMore()).isFalse();
        var component = response.components().items().get(0);
        assertThat(component.name()).isEqualTo("proj-lib");
        assertThat(component.version()).isEqualTo("1.0.0");
        assertThat(component.ecosystem()).isEqualTo("MAVEN");
        assertThat(component.projectId()).isEqualTo(1L);
        assertThat(component.projectName()).isEqualTo("project-1");

        assertThat(response.cves().items()).isEmpty();
        assertThat(response.cves().hasMore()).isFalse();
    }

    @Test
    @DisplayName("search is scoped to accessible project ids only")
    void scopedToAccessibleProjects() {
        List<Long> accessible = List.of(7L);
        when(projectAccessService.accessibleProjectIds()).thenReturn(accessible);
        when(projectRepository.searchByIdInAndName(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(scanComponentRepository.searchAccessibleComponents(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(cveRepository.searchAccessibleCves(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(new Object[]{"CVE-2021-44228", RiskLevel.CRITICAL, 7L, "shop"}));

        GlobalSearchResponse response = searchService.search("CVE-2021");

        verify(projectRepository).searchByIdInAndName(eq(accessible), eq("CVE-2021"), any(Pageable.class));
        verify(scanComponentRepository).searchAccessibleComponents(eq(accessible), eq("CVE-2021"), any(Pageable.class));
        verify(cveRepository).searchAccessibleCves(eq(accessible), eq("CVE-2021"), any(Pageable.class));

        assertThat(response.cves().items()).hasSize(1);
        var cve = response.cves().items().get(0);
        assertThat(cve.cveId()).isEqualTo("CVE-2021-44228");
        assertThat(cve.severity()).isEqualTo("CRITICAL");
        assertThat(cve.projectId()).isEqualTo(7L);
        assertThat(cve.projectName()).isEqualTo("shop");
    }

    @Test
    @DisplayName("no extra row means hasMore stays false")
    void exactLimit_noHasMore() {
        List<Long> accessible = List.of(1L);
        when(projectAccessService.accessibleProjectIds()).thenReturn(accessible);

        List<Object[]> rows = new ArrayList<>();
        for (long i = 1; i <= SearchService.GROUP_LIMIT; i++) {
            rows.add(new Object[]{i, "lib-project-" + i});
        }
        when(projectRepository.searchByIdInAndName(eq(accessible), eq("lib"), any(Pageable.class)))
                .thenReturn(rows);
        when(scanComponentRepository.searchAccessibleComponents(eq(accessible), eq("lib"), any(Pageable.class)))
                .thenReturn(List.of());
        when(cveRepository.searchAccessibleCves(eq(accessible), eq("lib"), any(Pageable.class)))
                .thenReturn(List.of());

        GlobalSearchResponse response = searchService.search("lib");

        assertThat(response.projects().items()).hasSize(SearchService.GROUP_LIMIT);
        assertThat(response.projects().hasMore()).isFalse();
    }
}
