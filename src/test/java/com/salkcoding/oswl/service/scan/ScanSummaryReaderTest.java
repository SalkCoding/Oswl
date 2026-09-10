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
