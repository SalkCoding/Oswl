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

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=${OSWL_SUMMARY_TEST_URL:jdbc:h2:mem:scan-summary;DB_CLOSE_DELAY=0;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT}",
        "spring.datasource.driver-class-name=${OSWL_SUMMARY_TEST_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${OSWL_SUMMARY_TEST_USER:sa}",
        "spring.datasource.password=${OSWL_SUMMARY_TEST_PASSWORD:}",
        "spring.jpa.database-platform=${OSWL_SUMMARY_TEST_DIALECT:org.hibernate.dialect.H2Dialect}"})
@Transactional
class ScanSummaryReaderTest {
    @Autowired ScanSummaryReader reader;
    @Autowired ScanArchivingService archive;
    @Autowired RiskTrendService trend;
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired ScanComponentRepository components;
    @Autowired LibraryRepository libraries;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Autowired com.salkcoding.oswl.service.vulnerability.SecurityCenterService securityCenter;

    @Test void riskSortDoesNotPromoteLowerSeverityByWeightedOverflow() {
        var project = projects.save(Project.builder().name("Risk-precedence-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).status(ScanStatus.COMPLETED).build());
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 2; i++) {
            var lib = Library.builder().name(i == 0 ? "a-many-medium" : "z-one-high").version("1").ecosystem("NPM").build();
            for (int j = 0; j < (i == 0 ? 1001 : 1); j++) {
                lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-" + (10000 + j))
                        .sources(java.util.Set.of(CveSource.OSV)).severity(i == 0 ? RiskLevel.MEDIUM : RiskLevel.HIGH).build());
            }
            libraries.save(lib);
            ids.add(components.save(ScanComponent.builder().scanResult(scan).library(lib).build()).getId());
        }
        entityManager.flush(); entityManager.clear();
        var result = securityCenter.queryRows(project.getId(), scan.getId(),
                com.salkcoding.oswl.dto.SecurityCenterRowFilterParams.initialPageLoad(), 0);
        assertThat(result.getContent()).extracting(com.salkcoding.oswl.dto.ComponentRowDto::getId)
                .containsExactly(ids.get(1), ids.get(0));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NVD", "CPE", "confidence", "package"})
    void riskSortUsesNonCandidateSeverity(String evidence) {
        var project = projects.save(Project.builder().name("Risk-sort-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).status(ScanStatus.COMPLETED).build());
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var lib = Library.builder().name(i == 0 ? "a-candidate" : i == 1 ? "b-high" : "c-unscored")
                    .version("1").ecosystem("NPM").build();
            java.util.Set<CveSource> sources = i > 0 ? java.util.Set.of(CveSource.OSV) : switch (evidence) {
                case "NVD" -> java.util.Set.of(CveSource.NVD);
                case "CPE" -> java.util.Set.of(CveSource.CPE);
                case "confidence" -> java.util.Set.of();
                default -> java.util.Set.of(CveSource.NVD, CveSource.OSV);
            };
            lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-123453").sources(sources)
                    .matchConfidence(i == 0 && evidence.equals("confidence") ? MatchConfidence.LOW : null)
                    .severity(i == 0 ? RiskLevel.CRITICAL : i == 1 ? RiskLevel.HIGH : null).build());
            libraries.save(lib);
            ids.add(components.save(ScanComponent.builder().scanResult(scan).library(lib).build()).getId());
        }
        entityManager.flush(); entityManager.clear();
        var result = securityCenter.queryRows(project.getId(), scan.getId(),
                com.salkcoding.oswl.dto.SecurityCenterRowFilterParams.initialPageLoad(), 0);
        assertThat(result.getContent()).extracting(com.salkcoding.oswl.dto.ComponentRowDto::getId)
                .containsExactlyElementsOf(evidence.equals("package") ? ids : List.of(ids.get(1), ids.get(2), ids.get(0)));
    }

    @Test void patchFilterCountsMatchesAcrossUnmatchedPages() {
        var project = projects.save(Project.builder().name("Patch-pages-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).status(ScanStatus.COMPLETED).build());
        var expected = new java.util.ArrayList<Long>();
        for (int i = 0; i < 211; i++) {
            boolean complete = i >= 110;
            var lib = Library.builder().name(String.format("patch-page-%03d", i)).version("1").ecosystem("NPM")
                    .fetchedAt(LocalDateTime.now()).vulnerabilityLookupAt(LocalDateTime.now())
                    .vulnerabilityLookupOutcomes(java.util.Map.of("OSV", complete ? "RESOLVED" : "UNAVAILABLE")).build();
            lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-123452").severity(RiskLevel.HIGH)
                    .sources(java.util.Set.of(CveSource.OSV)).fixVersion("2").build());
            libraries.save(lib);
            Long id = components.save(ScanComponent.builder().scanResult(scan).library(lib).build()).getId();
            if (complete) expected.add(id);
        }
        entityManager.flush(); entityManager.clear();
        var f = new com.salkcoding.oswl.dto.SecurityCenterRowFilterParams(null, false,
                false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false,
                true, false, false, false, false, "name");
        var first = securityCenter.queryRows(project.getId(), scan.getId(), f, 0);
        var second = securityCenter.queryRows(project.getId(), scan.getId(), f, 1);
        var empty = securityCenter.queryRows(project.getId(), scan.getId(), f, 2);
        assertThat(first.getContent()).extracting(com.salkcoding.oswl.dto.ComponentRowDto::getId).containsExactlyElementsOf(expected.subList(0, 100));
        assertThat(second.getContent()).extracting(com.salkcoding.oswl.dto.ComponentRowDto::getId).containsExactly(expected.get(100));
        assertThat(first.getTotalElements()).isEqualTo(101);
        assertThat(second.getTotalElements()).isEqualTo(101);
        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isEqualTo(101);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.hasNext()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"patchable", "nonPatchable", "deprecated", "outdated", "latest"})
    void patchFiltersUseDisplayedAssessment(String filter) {
        var project = projects.save(Project.builder().name("Patch-filter-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).status(ScanStatus.COMPLETED).build());
        java.util.Map<String, Long> ids = new java.util.HashMap<>();
        for (String state : List.of("fixed", "unfixed", "failed", "candidate", "missingSeverity", "deprecated", "outdated", "latest", "candidateLatest")) {
            var lib = Library.builder().name(state + UUID.randomUUID()).version("1").ecosystem("NPM")
                    .fetchedAt(LocalDateTime.now()).vulnerabilityLookupAt(LocalDateTime.now())
                    .vulnerabilityLookupOutcomes(java.util.Map.of("OSV", state.equals("failed") ? "UNAVAILABLE" : "RESOLVED"))
                    .deprecated(state.equals("deprecated") ? "retired" : null)
                    .isLatestVersion(state.equals("latest") || state.equals("candidateLatest"))
                    .build();
            if (!List.of("deprecated", "outdated", "latest").contains(state)) {
                lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-123451")
                        .sources(java.util.Set.of(state.startsWith("candidate") ? CveSource.NVD : CveSource.OSV))
                        .severity(state.equals("missingSeverity") ? null : RiskLevel.HIGH)
                        .fixVersion(state.equals("unfixed") ? null : "2").build());
            }
            libraries.save(lib);
            ids.put(state, components.save(ScanComponent.builder().scanResult(scan).library(lib).build()).getId());
        }
        entityManager.flush(); entityManager.clear();
        var f = new com.salkcoding.oswl.dto.SecurityCenterRowFilterParams(null, false,
                false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false,
                filter.equals("patchable"), filter.equals("nonPatchable"), filter.equals("deprecated"),
                filter.equals("outdated"), filter.equals("latest"), "name");
        var expected = switch (filter) {
            case "patchable" -> List.of(ids.get("fixed"), ids.get("missingSeverity"));
            case "nonPatchable" -> List.of(ids.get("unfixed"));
            default -> List.of(ids.get(filter));
        };
        var result = securityCenter.queryRows(project.getId(), scan.getId(), f, 0);
        assertThat(result.getContent()).extracting(com.salkcoding.oswl.dto.ComponentRowDto::getId)
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.getTotalElements()).isEqualTo(expected.size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE", "missing"})
    void severityFiltersExcludeCandidatesAndIncludeMissingSeverity(String level) {
        var project = projects.save(Project.builder().name("Filter-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).status(ScanStatus.COMPLETED).build());
        RiskLevel severity = level.equals("missing") ? null : RiskLevel.valueOf(level);
        java.util.List<Long> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var lib = Library.builder().name("filter-" + UUID.randomUUID()).version("1").ecosystem("NPM").build();
            var sources = i == 0 ? java.util.Set.of(CveSource.NVD) : i == 1
                    ? java.util.Set.of(CveSource.NVD, CveSource.OSV) : java.util.Set.of(CveSource.GITHUB_ADVISORY);
            lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-123450").severity(severity).sources(sources).build());
            libraries.save(lib);
            var component = components.save(ScanComponent.builder().scanResult(scan).library(lib).build());
            if (i > 0) expected.add(component.getId());
        }
        entityManager.flush();
        entityManager.clear();
        var result = components.searchForSecurityCenter(scan.getId(), null,
                false, false, false, false, false, false, false, false, false,
                level.equals("CRITICAL"), level.equals("HIGH"), level.equals("MEDIUM"), level.equals("LOW"),
                level.equals("NONE") || level.equals("missing"),
                false, false, false, false,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(result.getContent()).extracting(ScanComponent::getId).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"preserved", "legacy", "missing", "conflict"})
    void archiveExportUsesTheSelectedScansEvidence(String state) throws Exception {
        var project = projects.save(Project.builder().name("Export-" + UUID.randomUUID()).build());
        var library = Library.builder().name("export-" + UUID.randomUUID()).version("1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.RESTRICTED).build();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123450")
                .severity(RiskLevel.HIGH).fixVersion("2.0.0")
                .sources(java.util.Set.of(CveSource.OSV))
                .fixVersionConflictCandidates(state.equals("conflict") ? java.util.Set.of("2.0.0", "3.0.0") : java.util.Set.of())
                .cvssScore(8.1).epssScore(0.25).kevListed(true).build());
        library.recordLookupOutcomes(java.util.Map.of("OSV", "UNAVAILABLE"));
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
            var serialized = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .readTree(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(exported));
            if (json == null) assertThat(serialized.get(0).has("assessmentJson")).isTrue();
            if (json == null) assertThat(serialized.get(0).get("assessmentJson").isNull()).isTrue();
            else {
                assertThat(serialized.get(0).path("assessmentJson").asText()).isEqualTo(json);
                assertThat(ScanAssessmentService.read(serialized.get(0).get("assessmentJson").asText()).libraries())
                        .singleElement().satisfies(evidence -> {
                            assertThat(evidence.lookupOutcomes()).containsEntry("OSV", "UNAVAILABLE");
                            assertThat(evidence.findings()).singleElement().satisfies(finding -> {
                                assertThat(finding.sources()).containsExactly(CveSource.OSV);
                                if (state.equals("conflict")) {
                                    assertThat(finding.fixVersion()).isNull();
                                    assertThat(finding.fixVersionConflictCandidates()).containsExactlyInAnyOrder("2.0.0", "3.0.0");
                                }
                            });
                        });
            }
            assertThat(exported).singleElement().satisfies(record -> {
                assertThat(record.scanId()).isEqualTo(old.getId());
                assertThat(record.components()).hasSize(2).allSatisfy(component -> {
                    assertThat(component.licenseStatus()).isEqualTo(state.equals("legacy") ? "PERMITTED" : "RESTRICTED");
                    assertThat(component.licenseName()).isEqualTo(state.equals("legacy") ? "MIT" : null);
                    assertThat(component.cves()).singleElement().satisfies(finding -> {
                            assertThat(finding.cveId()).isEqualTo(state.equals("legacy") ? "CVE-2026-123451" : "CVE-2026-123450");
                            assertThat(finding.fixVersion()).isEqualTo(state.equals("legacy") ? "9.0.0" : state.equals("conflict") ? null : "2.0.0");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void archiveExportRejectsPreservedLibrariesMissingFromInventory(boolean emptyInventory) throws Exception {
        var project = projects.save(Project.builder().name("Lost-inventory-" + UUID.randomUUID()).build());
        var first = libraries.saveAndFlush(Library.builder().name("first-" + UUID.randomUUID()).version("1").ecosystem("NPM").build());
        var lost = Library.builder().name("lost-" + UUID.randomUUID()).version("1").ecosystem("NPM").build();
        lost.getCves().add(Cve.builder().library(lost).cveId("CVE-2026-123452").severity(RiskLevel.CRITICAL).fixVersion("2").build());
        lost = libraries.saveAndFlush(lost);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.salkcoding.oswl.dto.scan.ScanAssessment(1, "2026-09-01T00:00:00Z",
                        List.of(ScanAssessmentService.fromLibrary(first), ScanAssessmentService.fromLibrary(lost))));
        var old = scans.saveAndFlush(ScanResult.builder().project(project).version("1.0")
                .status(ScanStatus.COMPLETED).assessmentJson(json).build());
        if (!emptyInventory) components.saveAndFlush(ScanComponent.builder().scanResult(old).library(first).build());
        scans.saveAndFlush(ScanResult.builder().project(project).version("2.0").status(ScanStatus.COMPLETED).build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> archive.exportPendingArchive(project.getId(), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(old.getAssessmentJson()).isEqualTo(json);
        assertThat(old.isArchived()).isFalse();
        assertThat(components.countByScanResultId(old.getId())).isEqualTo(emptyInventory ? 0 : 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cpeCandidatesDoNotBecomeConfirmedSummaryCounts(boolean preserved) throws Exception {
        var project = projects.save(Project.builder().name("Candidate-summary-" + UUID.randomUUID()).build());
        var library = Library.builder().name("native-" + UUID.randomUUID()).version("1").ecosystem("CONAN").build();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123450")
                .sources(java.util.Set.of(CveSource.NVD)).severity(RiskLevel.CRITICAL).kevListed(true).build());
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123451")
                .matchConfidence(MatchConfidence.LOW).build());
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123452")
                .sources(java.util.Set.of(CveSource.NVD, CveSource.OSV)).severity(RiskLevel.HIGH).build());
        library = libraries.saveAndFlush(library);
        String json = preserved ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.salkcoding.oswl.dto.scan.ScanAssessment(1, "fixture", List.of(ScanAssessmentService.fromLibrary(library)))) : null;
        var old = scans.saveAndFlush(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED)
                .assessmentJson(json).build());
        components.save(ScanComponent.builder().scanResult(old).library(library).build());
        components.saveAndFlush(ScanComponent.builder().scanResult(old).library(library).build());
        scans.saveAndFlush(ScanResult.builder().project(project).version("2.0").status(ScanStatus.COMPLETED).build());
        var summary = reader.read(List.of(old)).get(old.getId());
        assertThat(summary.security()).containsExactly(0, 1, 0, 0, 0);
        assertThat(summary.matchReviewCount()).isEqualTo(2);
        assertThat(libraries.countPortfolioKev(List.of(old.getId()))).isEmpty();
        assertThat(library.getCves()).hasSize(3);
        assertThat(archive.archiveProject(project.getId(), 1).archivedNow()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        var archived = reader.read(List.of(scans.findById(old.getId()).orElseThrow())).get(old.getId());
        assertThat(archived.security()).containsExactly(summary.security());
        assertThat(archived.matchReviewCount()).isEqualTo(2);
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
