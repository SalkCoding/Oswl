package com.salkcoding.oswl.service.org;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.OrgProjectRiskDto;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count regression test for the org dashboard. {@code OrgDashboardService.populateModel()}
 * used to issue one completed-scans query per project plus one components query (and one
 * CVE-sources query per CVE) per scan aggregated by the posture totals and the trend walk; it now
 * batches all of that into a handful of fixed queries regardless of how many projects/scans exist.
 * This test locks in an upper bound (with headroom, not exact equality) so a future change can't
 * silently reintroduce per-row querying.
 *
 * Unlike the projects list, the org dashboard intentionally reads <b>all</b> non-deleted projects
 * (no user scoping — it's the admin roll-up), so leftover rows from other {@code @SpringBootTest}
 * classes sharing this context/DB do flow into the page. That doesn't skew the measurement: after
 * the batching fix the query count is fixed no matter how many extra projects leak in, and the
 * assertions below only check that our own fixture projects are present.
 */
@SpringBootTest
@DisplayName("OrgDashboardService.populateModel() 쿼리 수 회귀 테스트")
class OrgDashboardServiceQueryCountTest {

    @Autowired OrgDashboardService orgDashboardService;
    @Autowired ProjectRepository projectRepository;
    @Autowired LibraryRepository libraryRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        // A plain TransactionTemplate commits for real — this class isn't @Transactional, so
        // without an explicit transaction here the deletes below fail, and even if wrapped in
        // @Transactional on the method Spring Test would roll them back at method end anyway,
        // leaving rows behind for the next test that shares this context/DB.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                        .filter(p -> p.getName() != null && p.getName().startsWith("D2-OrgDash-"))
                        .forEach(projectRepository::delete));
    }

    /**
     * One project with two completed scans three weeks apart (so the trend's as-of walk references
     * both), each holding {@code componentCount} distinct libraries with 2 CVEs apiece.
     */
    private Project seedProject(String name, int componentCount) {
        Project project = projectRepository.save(Project.builder().name(name).build());

        List<Library> libraries = new ArrayList<>();
        for (int i = 0; i < componentCount; i++) {
            Library library = Library.builder()
                    .name(name + "-lib-" + i).version("1.0").ecosystem("MAVEN")
                    .licenseStatus(LicenseStatus.PERMITTED)
                    .build();
            library.getCves().add(Cve.builder().library(library)
                    .cveId(name + "-CVE-" + i + "-a").severity(RiskLevel.HIGH).cvssScore(7.5).build());
            library.getCves().add(Cve.builder().library(library)
                    .cveId(name + "-CVE-" + i + "-b").severity(RiskLevel.MEDIUM).cvssScore(5.0).build());
            libraries.add(libraryRepository.save(library));
        }

        addScan(project, libraries, "0.9.0", LocalDateTime.now().minusWeeks(3));
        addScan(project, libraries, "1.0.0", LocalDateTime.now());
        return projectRepository.save(project);
    }

    private void addScan(Project project, List<Library> libraries, String version, LocalDateTime scannedAt) {
        ScanResult scan = ScanResult.builder()
                .project(project).version(version).status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(scannedAt);
        for (Library library : libraries) {
            scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(library).build());
        }
        project.getScanResults().add(scan);
    }

    @Test
    @DisplayName("프로젝트 3개(스캔 2개씩)에서 populateModel()의 쿼리 수가 상한을 넘지 않는다")
    void populateModel_queryCount_staysWithinBound() {
        seedProject("D2-OrgDash-1", 2);
        seedProject("D2-OrgDash-2", 3);
        seedProject("D2-OrgDash-3", 1);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        Model model = new ExtendedModelMap();
        orgDashboardService.populateModel(model);

        long queries = stats.getPrepareStatementCount();

        assertThat(model.getAttribute("chartLabels")).asList().hasSize(10);
        @SuppressWarnings("unchecked")
        List<OrgProjectRiskDto> rows = (List<OrgProjectRiskDto>) model.getAttribute("projectRows");
        assertThat(rows).extracting(OrgProjectRiskDto::getName)
                .contains("D2-OrgDash-1", "D2-OrgDash-2", "D2-OrgDash-3");

        // Fixed at 4 for this fixture (project list: 1, findCompletedByProjectIdIn: 1,
        // findByIdInWithComponentsAndLibrary: 1, findByScanResultIdInWithCves: 1) — down from a
        // measured 25 before the batching fix (one findCompletedByProjectId per project, one
        // components query per latest scan and per trend-referenced scan, and one EAGER
        // Cve.sources select per CVE). The key property this test protects is that the count
        // does NOT scale with project, scan, component, or CVE count — it stays at this fixed
        // number whether there are 3 projects or 300. Locked with headroom (8) rather than exact
        // equality so an unrelated added query elsewhere doesn't make this flaky, while a real
        // regression (a new per-row query) still blows well past it.
        assertThat(queries).as("OrgDashboardService.populateModel() prepared-statement count")
                .isLessThanOrEqualTo(8);
    }
}
