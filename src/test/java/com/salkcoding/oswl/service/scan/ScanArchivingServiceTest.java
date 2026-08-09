package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.scan.ScanArchiveExportDto;
import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness test for the scan retention policy — the bulk-delete path is easy to get subtly
 * wrong (orphaned rows, wrong aggregate counts), and this session had no live app to click
 * through, so this is the only verification the archiving logic actually got.
 */
@SpringBootTest
@DisplayName("ScanArchivingService 통합 테스트")
class ScanArchivingServiceTest {

    @Autowired ScanArchivingService scanArchivingService;
    @Autowired ProjectRepository projectRepository;
    @Autowired ScanResultRepository scanResultRepository;
    @Autowired ScanComponentRepository scanComponentRepository;
    @Autowired LibraryRepository libraryRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private Project project;

    @AfterEach
    void cleanup() {
        if (project == null) return;
        Long projectId = project.getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                projectRepository.findById(projectId).ifPresent(projectRepository::delete));
    }

    private ScanResult seedScan(Project project, String version, LocalDateTime scannedAt, int componentCount) {
        ScanResult scan = ScanResult.builder()
                .project(project).version(version).status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(scannedAt);

        for (int i = 0; i < componentCount; i++) {
            Library library = Library.builder()
                    .name(version + "-lib-" + i).version("1.0").ecosystem("MAVEN")
                    .licenseStatus(LicenseStatus.RESTRICTED)
                    .build();
            library.getCves().add(Cve.builder().library(library)
                    .cveId(version + "-CVE-" + i).severity(RiskLevel.CRITICAL).cvssScore(9.8).build());
            library = libraryRepository.save(library);
            scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(library).build());
        }
        project.getScanResults().add(scan);
        return scan;
    }

    @Test
    @DisplayName("보존 개수를 넘는 오래된 스캔만 아카이빙되고, 컴포넌트가 삭제되며, 집계가 정확히 남는다")
    void archiveProject_archivesOnlyOldScans_deletesComponents_keepsAggregate() {
        project = projectRepository.save(Project.builder().name("D3-Archive-Test").build());

        seedScan(project, "1.0.0", LocalDateTime.now().minusDays(10), 2);
        seedScan(project, "2.0.0", LocalDateTime.now(), 1);
        project = projectRepository.save(project);

        // save() on an already-persisted Project merges (returns a new managed copy) rather than
        // mutating the original scan objects in place, so re-fetch ids explicitly instead of
        // trusting the `older`/`newer` references above to have been assigned one.
        Long olderId = scanResultRepository.findByProjectIdAndVersion(project.getId(), "1.0.0").orElseThrow().getId();
        Long newerId = scanResultRepository.findByProjectIdAndVersion(project.getId(), "2.0.0").orElseThrow().getId();

        ScanArchiveResult result = scanArchivingService.archiveProject(project.getId(), 1);

        assertThat(result.archivedNow()).isEqualTo(1);
        assertThat(result.totalCompletedScans()).isEqualTo(2);

        ScanResult reloadedOlder = scanResultRepository.findById(olderId).orElseThrow();
        assertThat(reloadedOlder.isArchived()).isTrue();
        assertThat(reloadedOlder.getArchivedComponentCount()).isEqualTo(2);
        assertThat(reloadedOlder.getArchivedSecurityCritical()).isEqualTo(2);
        assertThat(reloadedOlder.getArchivedLicenseCritical()).isEqualTo(2);
        assertThat(scanComponentRepository.countByScanResultId(olderId)).isZero();

        ScanResult reloadedNewer = scanResultRepository.findById(newerId).orElseThrow();
        assertThat(reloadedNewer.isArchived()).isFalse();
        assertThat(scanComponentRepository.countByScanResultId(newerId)).isEqualTo(1);

        // Idempotent: running again with the same retain count archives nothing further.
        ScanArchiveResult second = scanArchivingService.archiveProject(project.getId(), 1);
        assertThat(second.archivedNow()).isZero();
    }

    @Test
    @DisplayName("exportPendingArchive는 삭제 전 컴포넌트/CVE 상세를 반환하고 실제로는 아무것도 지우지 않는다")
    void exportPendingArchive_returnsDetailWithoutDeleting() {
        project = projectRepository.save(Project.builder().name("D3-Export-Test").build());

        // Distinct version strings from the other test in this class — seedScan() names each
        // library "<version>-lib-<i>", and Library rows aren't cleaned up between tests (they're
        // a shared catalog, not owned by Project), so reusing "1.0.0"/"2.0.0" here would collide
        // with the other test's leftover rows on the (name, version, ecosystem) unique constraint.
        seedScan(project, "3.0.0", LocalDateTime.now().minusDays(10), 2);
        seedScan(project, "4.0.0", LocalDateTime.now(), 1);
        project = projectRepository.save(project);

        Long olderId = scanResultRepository.findByProjectIdAndVersion(project.getId(), "3.0.0").orElseThrow().getId();

        List<ScanArchiveExportDto> export = scanArchivingService.exportPendingArchive(project.getId(), 1);

        assertThat(export).hasSize(1);
        ScanArchiveExportDto exported = export.getFirst();
        assertThat(exported.scanId()).isEqualTo(olderId);
        assertThat(exported.version()).isEqualTo("3.0.0");
        assertThat(exported.components()).hasSize(2);
        assertThat(exported.components()).allSatisfy(c -> assertThat(c.cves()).hasSize(1));
        assertThat(exported.components().stream()
                .flatMap(c -> c.cves().stream())
                .map(ScanArchiveExportDto.CveExportDto::severity))
                .containsOnly("CRITICAL");

        // Read-only: the scan must still be fully intact so a subsequent real archive can delete it.
        assertThat(scanComponentRepository.countByScanResultId(olderId)).isEqualTo(2);
        assertThat(scanResultRepository.findById(olderId).orElseThrow().isArchived()).isFalse();

        // Once actually archived, the same scan is no longer "pending" — nothing left to export.
        scanArchivingService.archiveProject(project.getId(), 1);
        assertThat(scanArchivingService.exportPendingArchive(project.getId(), 1)).isEmpty();
    }
}
