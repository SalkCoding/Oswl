package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.reporting.RiskTrendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.ConcurrentModel;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ScanSummaryReaderTest {
    @Autowired ScanSummaryReader reader;
    @Autowired ScanArchivingService archive;
    @Autowired RiskTrendService trend;
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired ScanComponentRepository components;
    @Autowired LibraryRepository libraries;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"preserved", "legacy", "missing"})
    void archiveExportUsesTheSelectedScansEvidence(String state) throws Exception {
        var project = projects.save(Project.builder().name("Export-" + UUID.randomUUID()).build());
        var library = Library.builder().name("export-" + UUID.randomUUID()).version("1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.RESTRICTED).build();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123450")
                .severity(RiskLevel.HIGH).fixVersion("2.0.0").cvssScore(8.1).epssScore(0.25).kevListed(true).build());
        library = libraries.saveAndFlush(library);
        String json = state.equals("legacy") ? null : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.salkcoding.oswl.dto.scan.ScanAssessment(1, "2026-09-01T00:00:00Z",
                        state.equals("missing") ? List.of() : List.of(ScanAssessmentService.fromLibrary(library))));
        var old = scans.saveAndFlush(ScanResult.builder().project(project).version("1.0")
                .status(ScanStatus.COMPLETED).assessmentJson(json).build());
        components.save(ScanComponent.builder().scanResult(old).library(library).build());
        components.saveAndFlush(ScanComponent.builder().scanResult(old).library(library).build());
        scans.saveAndFlush(ScanResult.builder().project(project).version("2.0").status(ScanStatus.COMPLETED).build());
        library.updateLicense("MIT", LicenseStatus.PERMITTED);
        library.getCves().clear();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123451")
                .severity(RiskLevel.LOW).fixVersion("9.0.0").cvssScore(2.0).epssScore(0.01).kevListed(false).build());
        libraries.saveAndFlush(library);
        if (state.equals("missing")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> archive.exportPendingArchive(project.getId(), 1))
                    .isInstanceOf(IllegalStateException.class);
        } else {
            var exported = archive.exportPendingArchive(project.getId(), 1);
            assertThat(exported).singleElement().satisfies(record -> {
                assertThat(record.scanId()).isEqualTo(old.getId());
                assertThat(record.components()).hasSize(2).allSatisfy(component -> {
                    assertThat(component.licenseStatus()).isEqualTo(state.equals("legacy") ? "PERMITTED" : "RESTRICTED");
                    assertThat(component.licenseName()).isEqualTo(state.equals("legacy") ? "MIT" : null);
                    assertThat(component.cves()).singleElement().satisfies(finding -> {
                            assertThat(finding.cveId()).isEqualTo(state.equals("legacy") ? "CVE-2026-123451" : "CVE-2026-123450");
                            assertThat(finding.fixVersion()).isEqualTo(state.equals("legacy") ? "9.0.0" : "2.0.0");
                            assertThat(finding.severity()).isEqualTo(state.equals("legacy") ? "LOW" : "HIGH");
                            assertThat(finding.cvssScore()).isEqualTo(state.equals("legacy") ? 2.0 : 8.1);
                            assertThat(finding.epssScore()).isEqualTo(state.equals("legacy") ? 0.01 : 0.25);
                            assertThat(finding.kevListed()).isEqualTo(!state.equals("legacy"));
                        });
                });
            });
        }
        assertThat(components.countByScanResultId(old.getId())).isEqualTo(2);
        assertThat(old.isArchived()).isFalse();
        assertThat(old.getAssessmentJson()).isEqualTo(json);
    }

    @Test void duplicateComponentsDoNotMultiplyCountsAndArchivePreservesTrend() {
        var project = projects.save(Project.builder().name("Summary-" + UUID.randomUUID()).build());
        var library = Library.builder().name("lib-" + UUID.randomUUID()).version("1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.RESTRICTED).build();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-CRITICAL").severity(RiskLevel.CRITICAL).build());
        library.getCves().add(Cve.builder().library(library).cveId("CVE-UNSCORED").build());
        library = libraries.saveAndFlush(library);
        var old = ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build();
        old.setScannedAt(LocalDateTime.now().minusDays(2));
        old = scans.saveAndFlush(old);
        components.save(ScanComponent.builder().scanResult(old).library(library).build());
        components.saveAndFlush(ScanComponent.builder().scanResult(old).library(library).build());
        var latest = ScanResult.builder().project(project).version("2.0").status(ScanStatus.COMPLETED).build();
        latest.setScannedAt(LocalDateTime.now());
        scans.saveAndFlush(latest);

        var before = reader.read(List.of(old)).get(old.getId());
        assertThat(before.security()).containsExactly(1, 0, 0, 0, 1);
        assertThat(before.licenses()).containsExactly(1, 0, 0, 0);
        var modelBefore = new ConcurrentModel();
        trend.populateModel(project.getId(), null, modelBefore);

        assertThat(archive.archiveProject(project.getId(), 1).archivedNow()).isEqualTo(1);
        var after = reader.read(List.of(scans.findById(old.getId()).orElseThrow())).get(old.getId());
        assertThat(after.security()).containsExactly(before.security());
        assertThat(after.licenses()).containsExactly(before.licenses());
        assertThat(components.countByScanResultId(old.getId())).isZero();
        var modelAfter = new ConcurrentModel();
        trend.populateModel(project.getId(), null, modelAfter);
        assertThat(modelAfter.getAttribute("chartSecCritical")).isEqualTo(modelBefore.getAttribute("chartSecCritical"));
    }
    @Test void kevUsesDistinctCvesAndRemainsOpenUntilEveryOccurrenceIsAddressed() {
        var project = projects.save(Project.builder().name("KEV-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        var lib = Library.builder().name("kev-" + UUID.randomUUID()).version("1").ecosystem("NPM").build();
        lib.getCves().add(Cve.builder().library(lib).cveId("CVE-KEV").severity(RiskLevel.HIGH).kevListed(true).build());
        lib = libraries.saveAndFlush(lib);
        var first = components.save(ScanComponent.builder().scanResult(scan).library(lib).reviewed(true).build());
        var second = components.saveAndFlush(ScanComponent.builder().scanResult(scan).library(lib).build());
        var counts = libraries.countPortfolioKev(List.of(scan.getId())).getFirst();
        assertThat(((Number)counts[1]).longValue()).isEqualTo(1);
        assertThat(((Number)counts[2]).longValue()).isEqualTo(1);
        second.markReviewed(true); components.saveAndFlush(second);
        assertThat(((Number)libraries.countPortfolioKev(List.of(scan.getId())).getFirst()[2]).longValue()).isZero();
    }

}
