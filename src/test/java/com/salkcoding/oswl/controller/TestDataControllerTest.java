package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.RoleTemplateBootstrapService;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectMember;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.DependencyPathRepository;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("TestDataController /data/test 시드 검증")
class TestDataControllerTest {

    @Autowired TestDataController testDataController;
    @Autowired ProjectRepository projectRepository;
    @Autowired LibraryRepository libraryRepository;
    @Autowired ScanResultRepository scanResultRepository;
    @Autowired ScanComponentRepository scanComponentRepository;
    @Autowired DependencyPathRepository dependencyPathRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleTemplateRepository roleTemplateRepository;
    @Autowired RoleTemplateBootstrapService roleTemplateBootstrapService;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureSystemAdmin() {
        if (!userRepository.existsByIsSystemAdminTrue()) {
            roleTemplateBootstrapService.ensureBuiltInTemplates();
            userRepository.save(User.builder()
                    .email("admin@oswl.local")
                    .passwordHash(passwordEncoder.encode("Admin!Test1234"))
                    .displayName("Test Admin")
                    .isSystemAdmin(true)
                    .enabled(true)
                    .build());
        }
    }

    private void seed() {
        testDataController.insertTestData();
    }

    @Nested
    @DisplayName("프로젝트·스캔 생명주기")
    class ProjectsAndScans {

        @Test
        @DisplayName("활성 5건·휴지통 1건·스캔 없는 empty-state 프로젝트")
        void projects_coverActiveTrashAndEmpty() {
            seed();

            List<Project> active = projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
            assertThat(active).extracting(Project::getName)
                    .containsExactlyInAnyOrder(
                            "backend-api", "frontend-dashboard", "ml-pipeline",
                            "new-service", "payment-gateway");

            assertThat(projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc())
                    .extracting(Project::getName)
                    .containsExactly("legacy-monolith");

            assertThat(scanResultRepository.findAll().stream()
                    .filter(s -> s.getProject().getName().equals("new-service"))
                    .findAny()).isEmpty();
        }

        @Test
        @DisplayName("backend-api는 8회 완료 스캔·FAILED·PENDING·SCANNING·ANALYZING 포함")
        void projectA_scanLifecycle() {
            seed();

            Project backend = findProject("backend-api");
            List<ScanResult> scans = scanResultRepository.findAll().stream()
                    .filter(s -> s.getProject().getId().equals(backend.getId()))
                    .toList();

            assertThat(scans.stream().filter(s -> s.getStatus() == ScanStatus.COMPLETED).count())
                    .isGreaterThanOrEqualTo(8);
            assertThat(scans).anyMatch(s -> s.getStatus() == ScanStatus.FAILED
                    && s.getErrorMessage() != null && !s.getErrorMessage().isBlank());
            assertThat(scans).anyMatch(s -> s.getStatus() == ScanStatus.PENDING);
            assertThat(scans).anyMatch(s -> s.getStatus() == ScanStatus.SCANNING);
            assertThat(scans).anyMatch(s -> s.getStatus() == ScanStatus.ANALYZING);
        }

        @Test
        @DisplayName("Quick Import 제출자(submittedByUserId) 스캔 존재")
        void scans_withSubmittedByUserId() {
            seed();

            assertThat(scanResultRepository.findAll()).anyMatch(s -> s.getSubmittedByUserId() != null);
        }

        @Test
        @DisplayName("GitHub·GitLab·CLI-only VCS 변형")
        void vcsProviderVariants() {
            seed();

            assertThat(findProject("backend-api").getVcsProvider().name()).isEqualTo("GITHUB");
            assertThat(findProject("payment-gateway").getVcsProvider().name()).isEqualTo("GITLAB");
            assertThat(findProject("ml-pipeline").getGithubRepo()).isNull();
        }
    }

    @Nested
    @DisplayName("라이브러리·CVE·라이선스")
    class LibrariesAndCves {

        @Test
        @DisplayName("MAVEN·NPM·PYPI 생태계와 라이선스 상태 혼합")
        void ecosystemsAndLicenseStatuses() {
            seed();

            List<Library> libs = libraryRepository.findAll();
            assertThat(libs).anyMatch(l -> "MAVEN".equals(l.getEcosystem()));
            assertThat(libs).anyMatch(l -> "NPM".equals(l.getEcosystem()));
            assertThat(libs).anyMatch(l -> "PYPI".equals(l.getEcosystem()));
            assertThat(libs).anyMatch(l -> l.getLicenseStatus() == LicenseStatus.RESTRICTED);
            assertThat(libs).anyMatch(l -> l.getLicenseStatus() == LicenseStatus.UNKNOWN);
            assertThat(libs).anyMatch(l -> l.getFetchedAt() == null);
        }

        @Test
        @DisplayName("CVE: GHSA-only·NON_PATCHABLE·심각도 혼합·AI 요약 유/무")
        void cveEdgeCasesAndAiSummaries() {
            seed();

            List<Cve> allCves = libraryRepository.findAll().stream()
                    .flatMap(l -> l.getCves().stream())
                    .toList();

            assertThat(allCves).anyMatch(c -> c.getCveId() == null && c.getGhsaId() != null);
            assertThat(allCves).anyMatch(c -> c.getFixVersion() == null);
            assertThat(allCves).extracting(Cve::getSeverity)
                    .contains(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW);
            assertThat(libsByName("com.google.guava:guava").getFirst().getCves()).isEmpty();

            long withAi = allCves.stream().filter(c -> c.getAiSummary() != null && !c.getAiSummary().isBlank()).count();
            long withoutAi = allCves.stream().filter(c -> c.getAiSummary() == null).count();
            assertThat(withAi).isGreaterThanOrEqualTo(8);
            assertThat(withoutAi).isGreaterThanOrEqualTo(20);

            Library log4j = libsByName("org.apache.logging.log4j:log4j-core").getFirst();
            assertThat(log4j.getCves()).anyMatch(c -> c.getAiSummary() != null);
            assertThat(log4j.getCves()).anyMatch(c -> c.getAiSummary() == null);
        }

        private List<Library> libsByName(String name) {
            return libraryRepository.findAll().stream()
                    .filter(l -> name.equals(l.getName()))
                    .toList();
        }
    }

    @Nested
    @DisplayName("스캔 컴포넌트·경로·예외(Deferral)")
    class ComponentsAndDeferrals {

        @Test
        @DisplayName("reviewed·ignored·legacy(경로 없음)·다중 DependencyPath")
        void componentFlagsAndPaths() {
            seed();

            List<ScanComponent> components = scanComponentRepository.findAll();
            assertThat(components).anyMatch(ScanComponent::isReviewed);
            assertThat(components).anyMatch(ScanComponent::isIgnored);

            ScanResult latestBackend = latestCompletedScan("backend-api");
            List<ScanComponent> latestComps = scanComponentRepository.findByScanResultId(latestBackend.getId());
            assertThat(latestComps).isNotEmpty();
            assertThat(dependencyPathRepository.findAll()).isNotEmpty();

            ScanResult oldestBackend = scanResultRepository.findAll().stream()
                    .filter(s -> s.getProject().getName().equals("backend-api"))
                    .filter(s -> "1.0.0".equals(s.getVersion()))
                    .findFirst()
                    .orElseThrow();
            long pathsOnOldest = scanComponentRepository.findByScanResultId(oldestBackend.getId()).stream()
                    .mapToLong(sc -> sc.getDependencyPaths().size())
                    .sum();
            assertThat(pathsOnOldest).isZero();
        }

        @Test
        @DisplayName("Deferral: legal-review·false-positive·temporary·wont-fix(만료)·other")
        void deferralReasons() {
            seed();

            List<ScanComponent> deferred = scanComponentRepository.findAll().stream()
                    .filter(sc -> sc.getDeferralReason() != null)
                    .toList();

            Set<String> reasons = deferred.stream()
                    .map(ScanComponent::getDeferralReason)
                    .collect(Collectors.toSet());
            assertThat(reasons).contains("legal-review", "false-positive", "temporary", "wont-fix", "other");

            assertThat(deferred).anyMatch(sc -> sc.getDeferralExpiresAt() == null);
            assertThat(deferred).anyMatch(sc -> sc.getDeferralExpiresAt() != null
                    && sc.getDeferralExpiresAt().isAfter(java.time.LocalDateTime.now()));
            assertThat(deferred).anyMatch(sc -> sc.getDeferralExpiresAt() != null
                    && sc.getDeferralExpiresAt().isBefore(java.time.LocalDateTime.now()));
        }
    }

    @Nested
    @DisplayName("권한 템플릿·QA 사용자·프로젝트 ACL")
    class AuthAndAcl {

        @Test
        @DisplayName("내장 Admin/Developer/Viewer·삭제 가능한 QA Custom 템플릿")
        void roleTemplates() {
            seed();

            assertThat(templateNames()).contains("Admin", "Developer", "Viewer", "QA Custom");

            RoleTemplate qaCustom = roleTemplateRepository.findAll().stream()
                    .filter(rt -> "QA Custom".equals(rt.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(qaCustom.isBuiltIn()).isFalse();
            assertThat(qaCustom.getPermissions()).containsExactlyInAnyOrder(
                    Permission.PROJECT_VIEW,
                    Permission.SCAN_VIEW,
                    Permission.COMPONENT_DETAIL_VIEW);
            assertThat(qaCustom.getPermissions()).doesNotContain(Permission.SECURITY_CENTER_UPDATE_STATUS);
        }

        @Test
        @DisplayName("QA 시드 사용자·비밀번호·비활성 계정")
        void qaUsers() {
            seed();

            User viewer = userRepository.findByEmail("viewer@oswl.local").orElseThrow();
            User developer = userRepository.findByEmail("developer@oswl.local").orElseThrow();
            User noProject = userRepository.findByEmail("noproject@oswl.local").orElseThrow();
            User disabled = userRepository.findByEmail("disabled@oswl.local").orElseThrow();
            User custom = userRepository.findByEmail("custom@oswl.local").orElseThrow();

            assertThat(passwordEncoder.matches(TestDataController.QA_SEED_PASSWORD, viewer.getPasswordHash()))
                    .isTrue();
            assertThat(viewer.getRoleTemplates()).extracting(RoleTemplate::getName).contains("Viewer");
            assertThat(developer.getRoleTemplates()).extracting(RoleTemplate::getName).contains("Developer");
            assertThat(custom.getRoleTemplates()).extracting(RoleTemplate::getName).contains("QA Custom");
            assertThat(disabled.isEnabled()).isFalse();
            assertThat(noProject.isEnabled()).isTrue();

            assertThat(projectMemberRepository.findProjectIdsByUserId(viewer.getId()))
                    .hasSize(2);
            assertThat(projectMemberRepository.findProjectIdsByUserId(developer.getId()))
                    .hasSize(3);
            assertThat(projectMemberRepository.findProjectIdsByUserId(noProject.getId()))
                    .isEmpty();
        }

        @Test
        @DisplayName("creator 프로젝트에 ADMIN 멤버십·developer는 backend-api ADMIN")
        void projectMemberships() {
            seed();

            Project backend = findProject("backend-api");
            User developer = userRepository.findByEmail("developer@oswl.local").orElseThrow();

            assertThat(projectMemberRepository.existsByProjectIdAndUserId(backend.getId(), developer.getId()))
                    .isTrue();
            assertThat(projectMemberRepository.findByProjectIdAndUserId(backend.getId(), developer.getId()))
                    .singleElement()
                    .extracting(ProjectMember::getRole)
                    .isEqualTo(ProjectMemberRole.ADMIN);
        }
    }

    @Nested
    @DisplayName("멱등성")
    class Idempotency {

        @Test
        @DisplayName("연속 두 번 시드해도 프로젝트·QA 사용자 수가 동일")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void doubleSeed_isStable() {
            seed();
            long projectsAfterFirst = projectRepository.count();
            long usersAfterFirst = userRepository.findAll().stream()
                    .filter(u -> u.getEmail().endsWith("@oswl.local") && !u.isSystemAdmin())
                    .count();

            seed();

            assertThat(projectRepository.count()).isEqualTo(projectsAfterFirst);
            assertThat(userRepository.findAll().stream()
                    .filter(u -> u.getEmail().endsWith("@oswl.local") && !u.isSystemAdmin())
                    .count()).isEqualTo(usersAfterFirst);
        }
    }

    private Project findProject(String name) {
        return projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Project not found: " + name));
    }

    private ScanResult latestCompletedScan(String projectName) {
        Project project = findProject(projectName);
        return scanResultRepository.findAll().stream()
                .filter(s -> s.getProject().getId().equals(project.getId()))
                .filter(s -> s.getStatus() == ScanStatus.COMPLETED)
                .max(java.util.Comparator.comparing(ScanResult::getScannedAt))
                .orElseThrow();
    }

    private Set<String> templateNames() {
        return roleTemplateRepository.findAll().stream()
                .map(RoleTemplate::getName)
                .collect(Collectors.toSet());
    }
}
