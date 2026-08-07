package com.salkcoding.oswl.service.project;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.project.ProjectMember;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectMemberRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count regression test for the projects list — the highest-traffic page in
 * the app. Locks in the current (known, not-yet-fixed) query count as an upper bound rather than
 * an exact figure, so unrelated changes don't make this flaky, but any further regression trips it.
 *
 * Isolated from other data via an explicit non-admin membership (not the systemAdmin
 * "see every project" path) so leftover rows from other {@code @SpringBootTest} classes sharing
 * this context/DB cannot skew the count.
 */
@SpringBootTest
@DisplayName("ProjectService.findAll() 쿼리 수 회귀 테스트")
class ProjectServiceQueryCountTest {

    private static final long TEST_USER_ID = 987_654_321L;

    @Autowired ProjectService projectService;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired LibraryRepository libraryRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        // A plain TransactionTemplate commits for real — this class isn't @Transactional, so
        // without an explicit transaction here the deletes below fail, and even if wrapped in
        // @Transactional on the method Spring Test would roll them back at method end anyway,
        // leaving rows behind for the next test that shares this context/DB.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            projectMemberRepository.deleteByUserId(TEST_USER_ID);
            projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                    .filter(p -> p.getName() != null && p.getName().startsWith("D2-QueryCount-"))
                    .forEach(projectRepository::delete);
        });
    }

    private void loginAsMember() {
        OswlUserPrincipal principal = new OswlUserPrincipal(
                TEST_USER_ID, "d2-query-count-test@test.local", "pw", "D2 Test User",
                false, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void addMember(Project project) {
        projectMemberRepository.save(ProjectMember.builder()
                .project(project).userId(TEST_USER_ID).role(ProjectMemberRole.MEMBER).build());
    }

    /** One project with a completed scan of {@code componentCount} distinct libraries, each with 2 CVEs. */
    private Project seedProject(String name, int componentCount) {
        Project project = projectRepository.save(Project.builder().name(name).build());

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.now());

        for (int i = 0; i < componentCount; i++) {
            Library library = Library.builder()
                    .name(name + "-lib-" + i).version("1.0").ecosystem("MAVEN")
                    .licenseStatus(LicenseStatus.PERMITTED)
                    .build();
            library.getCves().add(Cve.builder().library(library)
                    .cveId(name + "-CVE-" + i + "-a").severity(RiskLevel.HIGH).cvssScore(7.5).build());
            library.getCves().add(Cve.builder().library(library)
                    .cveId(name + "-CVE-" + i + "-b").severity(RiskLevel.MEDIUM).cvssScore(5.0).build());
            library = libraryRepository.save(library);

            scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(library).build());
        }

        project.getScanResults().add(scan);
        return projectRepository.save(project);
    }

    @Test
    @DisplayName("프로젝트 3개(컴포넌트 2/3/1개)에서 findAll()의 쿼리 수가 상한을 넘지 않는다")
    void findAll_queryCount_staysWithinBound() {
        Project p1 = seedProject("D2-QueryCount-1", 2);
        Project p2 = seedProject("D2-QueryCount-2", 3);
        Project p3 = seedProject("D2-QueryCount-3", 1);
        addMember(p1);
        addMember(p2);
        addMember(p3);
        loginAsMember();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        var result = projectService.findAll();

        long queries = stats.getPrepareStatementCount();
        assertThat(result).hasSize(3);

        // Currently measured: 28 for 3 projects / 6 total library-CVE pairs (own-user accessible-ids
        // + project list + alert-count, then per project: findLatestByProjectId, the lazy
        // ScanResult.components load, one SELECT per component for the EAGER Library association,
        // and one lazy Library.cves load per distinct library). Locked with headroom (35) rather
        // than exact equality so an unrelated field addition elsewhere doesn't make this flaky —
        // a real N+1 regression (e.g. a new per-component or per-CVE query) will still blow well
        // past this bound.
        // KNOWN, NOT YET FIXED: ScanResult.components and Library.cves are lazy-loaded per project/
        // component instead of being batch-fetched once — see ROADMAP.md's D2 section.
        assertThat(queries).as("ProjectService.findAll() prepared-statement count").isLessThanOrEqualTo(35);
    }
}
