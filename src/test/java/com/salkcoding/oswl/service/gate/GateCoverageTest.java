package com.salkcoding.oswl.service.gate;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.policy.PolicyService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GateCoverageTest {
    @Mock ProjectRepository projects;
    @Mock ScanResultRepository scans;
    @Mock ScanComponentRepository components;
    @Mock ScanFindingRepository findings;
    @Mock LibraryRepository libraries;
    @Mock PolicyService policies;
    @Mock OswlMetrics metrics;
    @InjectMocks GatePolicyService service;
    Project project;
    ScanResult scan;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "defaultFailOnSeverity", "HIGH");
        project = Project.builder().id(1L).name("Coverage").build();
        scan = ScanResult.builder().id(2L).project(project).status(ScanStatus.COMPLETED).build();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(scans.findRecentCompleted(1L, 1)).thenReturn(List.of(scan));
        when(policies.resolveGateOptions(1L)).thenReturn(GatePolicyService.GateOptions.defaults());
    }

    @Test void missingLookupCannotPassEvenWhenIgnoredOrFiltered() {
        Library library = Library.builder().id(3L).name("unsupported").version("1").build();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder()
                .library(library).ignored(true).scanResult(scan).build()));
        var result = service.evaluate(1L, new GatePolicyService.GateOptions(null, null, null, null, null, true, true, null));
        assertThat(result.passed()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.coverage().unanalysedComponents()).isEqualTo(1);
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("COVERAGE");
        assertThat(result.commentMarkdown()).contains("incomplete analysis coverage");
    }

    @Test void completedLookupWithNoFindingsCanPass() {
        Library library = Library.builder().id(3L).name("queried").version("1").build(); library.markFetched();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).scanResult(scan).build()));
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isTrue();
        assertThat(result.coverage().complete()).isTrue();
    }

    @Test void failedRefreshKeepsPriorEvidenceButCannotPassUntilSuccessfulRetry() {
        Library library = Library.builder().id(3L).name("cached").version("1").build(); library.markFetched();
        var prior = library.getFetchedAt();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).scanResult(scan).build()));
        library.recordLookupOutcomes(Map.of("OSV", "UNAVAILABLE"));
        assertThat(service.evaluate(1L, GatePolicyService.GateOptions.defaults()).passed()).isFalse();
        assertThat(library.getFetchedAt()).isEqualTo(prior);
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED")); library.markFetched();
        assertThat(service.evaluate(1L, GatePolicyService.GateOptions.defaults()).passed()).isTrue();
    }

    @Test void archivedDetailCannotPassAsAnEmptyScan() {
        scan.archive(12, new int[5], new int[4]);
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().detailsAvailable()).isFalse();
    }

    @Test void incompleteScannerCannotPassEvenWithSecretPolicyDisabled() {
        when(findings.hasIncompleteScanner(2L, 1L)).thenReturn(true);
        var result = service.evaluate(1L, new GatePolicyService.GateOptions(null,null,null,null,null,true,true,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("COVERAGE");
    }

    @Test void runningScanCannotPassBeforeEnrichment() {
        scan.startScanning();
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().scanCompleted()).isFalse();
    }
}
