package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.CveSource;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScanAssessmentServiceTest {
    @Test void retainsUnknownCoverageAndConflictingFixEvidenceWithoutInventingAResult() {
        var scans = mock(ScanResultRepository.class);
        var service = new ScanAssessmentService(scans);
        var library = Library.builder().id(5L).name("fixture").version("1").ecosystem("NPM").build();
        library.recordLookupOutcomes(Map.of("OSV","UNAVAILABLE"));
        library.getCves().add(Cve.builder().cveId("CVE-2026-123450").fixVersion("2")
                .fixVersionConflictCandidates(Set.of("2","3")).sources(Set.of(CveSource.OSV)).nvdApplicability("{\"configurations\":[]}").build());
        var component = ScanComponent.builder().library(library).build();
        service.capture(7L,List.of(component,component));
        var json = ArgumentCaptor.forClass(String.class);
        verify(scans).pinAssessmentIfAbsent(eq(7L),json.capture());
        library.getCves().clear();
        var assessment = ScanAssessmentService.read(json.getValue());
        assertThat(assessment.libraries()).singleElement().satisfies(saved -> {
            assertThat(saved.lookupOutcomes()).containsEntry("OSV","UNAVAILABLE");
            assertThat(saved.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.fixVersion()).isNull();
                assertThat(finding.fixVersionConflictCandidates()).containsExactlyInAnyOrder("2","3");
                assertThat(finding.kevListed()).isNull();
                assertThat(finding.cvssScore()).isNull();
                assertThat(finding.sources()).containsExactly(CveSource.OSV);
                assertThat(finding.nvdApplicability()).isEqualTo("{\"configurations\":[]}");
            });
        });
        assertThatThrownBy(() -> assessment.libraries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void unsupportedStoredFormatFailsInsteadOfReturningAnEmptyCleanAssessment() {
        assertThatThrownBy(() -> ScanAssessmentService.read("{\"formatVersion\":999,\"capturedAt\":\"fixture\",\"libraries\":[]}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
