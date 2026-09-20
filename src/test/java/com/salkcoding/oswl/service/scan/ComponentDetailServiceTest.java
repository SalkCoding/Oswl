package com.salkcoding.oswl.service.scan;
import com.salkcoding.oswl.service.vcs.GitLabService;
import com.salkcoding.oswl.service.vcs.BitbucketService;
import com.salkcoding.oswl.service.scan.ComponentDetailService;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import com.salkcoding.oswl.service.vcs.GitHubService;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.entity.project.*;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.repository.scan.DependencyPathRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComponentDetailService 단위 테스트")
class ComponentDetailServiceTest {

    @Mock ProjectRepository           projectRepository;
    @Mock ScanComponentRepository     scanComponentRepository;
    @Mock com.salkcoding.oswl.repository.scan.ScanResultRepository scanResultRepository;
    @Mock DependencyPathRepository    dependencyPathRepository;
    @Mock AuditLogService             auditLogService;
    @Mock GitHubService               gitHubService;
    @Mock GitLabService               gitLabService;
    @Mock BitbucketService            bitbucketService;
    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @Mock EncryptionService           encryptionService;
    @Mock ProjectAccessService        projectAccessService;
    @Mock com.salkcoding.oswl.service.vulnerability.RemediationTargetVerifier remediationTargetVerifier;
    @Mock org.springframework.context.MessageSource messageSource;

    @InjectMocks
    ComponentDetailService componentDetailService;

    @Test
    void batchSkipsRejectedTargetsAndCreatesOnlyTheVerifiedItem() {
        var rejected = Library.builder().id(10L).name("rejected").version("1.0.0").latestVersion("2.0.0").isLatestVersion(false).build();
        var accepted = Library.builder().id(11L).name("accepted").version("1.0.0").latestVersion("3.0.0").isLatestVersion(false).build();
        var project = Project.builder().id(1L).name("fixture").vcsProvider(VcsProvider.GITHUB).githubRepo("owner/repo").build();
        var scan = ScanResult.builder().id(30L).project(project).status(ScanStatus.COMPLETED).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findRecentCompleted(1L, 1)).thenReturn(List.of(scan));
        when(scanComponentRepository.findByScanResultId(30L)).thenReturn(List.of(
                ScanComponent.builder().id(20L).library(rejected).build(), ScanComponent.builder().id(21L).library(accepted).build()));
        doThrow(new com.salkcoding.oswl.service.vulnerability.RemediationTargetVerifier.UnverifiedTargetException("TARGET_HAS_OSV_FINDINGS"))
                .when(remediationTargetVerifier).requireVerifiedTarget(rejected, "2.0.0");
        when(messageSource.getMessage(eq("componentDetail.patch.targetUnverified"), isNull(), any(java.util.Locale.class))).thenReturn("Unverified target");
        when(gitHubService.createVersionBumpPr(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("prUrl", "https://github.com/owner/repo/pull/1", "prNumber", 1));
        var result = componentDetailService.createBatchPullRequests(1L, "main", 1L, "fixture-token");
        assertThat(result).containsEntry("created", 1).containsEntry("failed", 1);
        verify(remediationTargetVerifier).requireVerifiedTarget(accepted, "3.0.0");
        verify(gitHubService).createVersionBumpPr(eq("fixture-token"), eq("owner"), eq("repo"), eq("main"),
                eq("accepted"), eq("1.0.0"), eq("3.0.0"), any(), any(), any(), isNull());
        verifyNoMoreInteractions(gitHubService);
        verifyNoInteractions(gitLabService, bitbucketService);
    }

    @Test
    void verifiedTargetReachesVcsWithExactlyTheCheckedVersion() {
        var osv = mock(com.salkcoding.oswl.client.OsvClient.class);
        var deps = mock(com.salkcoding.oswl.client.DepsDevClient.class);
        var github = mock(com.salkcoding.oswl.client.GitHubAdvisoryClient.class);
        var generationRepository = org.mockito.Mockito.mock(com.salkcoding.oswl.repository.snapshot.SnapshotGenerationRepository.class);
        org.mockito.Mockito.lenient().when(generationRepository.activeId()).thenReturn(1L);
        org.mockito.Mockito.lenient().when(generationRepository.metadata(1L)).thenReturn("{}");
        var generations = new com.salkcoding.oswl.service.snapshot.SnapshotGenerationService(generationRepository,
                org.mockito.Mockito.mock(com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository.class),
                org.mockito.Mockito.mock(com.salkcoding.oswl.repository.DatabaseMutationLockRepository.class));
        var verifier = new com.salkcoding.oswl.service.vulnerability.RemediationTargetVerifier(osv, deps, github, generations);
        org.springframework.test.util.ReflectionTestUtils.setField(componentDetailService, "remediationTargetVerifier", verifier);
        var library = Library.builder().id(10L).name("example").version("1.0.0").ecosystem("NPM")
                .latestVersion("2.0.0").isLatestVersion(false).build();
        var component = ScanComponent.builder().id(20L).library(library).build();
        var project = Project.builder().id(1L).name("fixture").vcsProvider(VcsProvider.GITHUB).githubRepo("owner/repo").build();
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(osv.queryBatch(any())).thenReturn(List.of(new com.salkcoding.oswl.client.OsvClient.OsvResult(List.of()),
                new com.salkcoding.oswl.client.OsvClient.OsvResult(List.of())));
        when(deps.getVersionsBatch(any())).thenReturn(List.of(new com.salkcoding.oswl.client.DepsDevClient.VersionInfo(
                List.of(), List.of(), false, null, "99.0.0", true, null)));
        when(gitHubService.createVersionBumpPr(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("prUrl", "https://github.com/owner/repo/pull/1", "prNumber", 1));
        componentDetailService.createPullRequest(1L, 20L, buildCreatePrRequest("main", null, null), 1L, "fixture-token");
        var order = inOrder(osv, deps, gitHubService);
        order.verify(osv).queryBatch(List.of(new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"),
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "2.0.0")));
        order.verify(deps).getVersionsBatch(List.of(new com.salkcoding.oswl.client.DepsDevClient.ComponentKey("NPM", "example", "2.0.0")));
        order.verify(gitHubService).createVersionBumpPr(eq("fixture-token"), eq("owner"), eq("repo"), eq("main"),
                eq("example"), eq("1.0.0"), eq("2.0.0"), any(), any(), any(), isNull());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = VcsProvider.class, names = {"GITHUB", "GITLAB", "BITBUCKET"})
    void unverifiedTargetsAreRejectedBeforeEveryVcsMutation(VcsProvider provider) {
        var library = Library.builder().id(10L).name("example").version("1.0.0").ecosystem("NPM")
                .cves(List.of(Cve.builder().ghsaId("OSV-fixture").fixVersion("2.0.0").build())).build();
        library.markFetched();
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", Map.of("OSV-fixture", "2026-01-01T00:00:00Z"),
                java.util.Set.of("OSV-fixture"), java.time.Instant.now().plusSeconds(3600));
        var component = ScanComponent.builder().id(20L).library(library).build();
        var project = Project.builder().id(1L).name("fixture").vcsProvider(provider).githubRepo("owner/repo").build();
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        doThrow(new com.salkcoding.oswl.service.vulnerability.RemediationTargetVerifier.UnverifiedTargetException("TARGET_HAS_OSV_FINDINGS"))
                .when(remediationTargetVerifier).requireVerifiedTarget(library, "2.0.0");
        when(messageSource.getMessage(eq("componentDetail.patch.targetUnverified"), isNull(), any(java.util.Locale.class)))
                .thenReturn("The proposed version could not be verified.");
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, buildCreatePrRequest("main", null, null), 1L, "fixture-token"))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class).hasMessageContaining("could not be verified");
        verifyNoInteractions(gitHubService, gitLabService, bitbucketService, vcsConnectionRepository, encryptionService);
        verify(auditLogService).log("COMPONENT.CREATE_PR_WITHHELD", "COMPONENT", "20", "example",
                "targetVersion=2.0.0 reason=TARGET_HAS_OSV_FINDINGS");
    }

    // ── populateModel ────────────────────────────────────────────────────

    @Test void createPullRequest_rejectsLatestWhenSecurityFixIsUnknown() {
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITHUB).githubRepo("fixture/repo").build();
        Library library = Library.builder().name("fixture").version("1.0.0")
                .latestVersion("9.0.0").isLatestVersion(false)
                .cves(List.of(Cve.builder().severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH).build())).build();
        ScanComponent component = ScanComponent.builder().library(library).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L,
                buildCreatePrRequest("main", null, null), 1L, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No patch");
        verifyNoInteractions(gitHubService, gitLabService, bitbucketService);
    }

    @Test
    @DisplayName("프로젝트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsWhenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                componentDetailService.populateModel(99L, 1L, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("컴포넌트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsWhenComponentNotFound() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                componentDetailService.populateModel(1L, 99L, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("정상 케이스: 모델에 컴포넌트 정보가 채워진다")
    void populateModel_populatesModelAttributes() {
        Project project = Project.builder().id(1L).name("TestProject").build();

        Library lib = Library.builder()
                .id(10L)
                .name("lodash")
                .version("4.17.15")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();

        ScanResult scan = ScanResult.builder()
                .id(5L)
                .version("1.0.0")
                .status(ScanStatus.COMPLETED)
                .build();

        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getId()).thenReturn(20L);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.getScanResult()).thenReturn(scan);
        when(sc.isReviewed()).thenReturn(false);
        when(sc.getDependencyInfo()).thenReturn("direct");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(3L);

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        assertThat(model.getAttribute("projectName")).isEqualTo("TestProject");
        assertThat(model.getAttribute("componentName")).isEqualTo("lodash");
        assertThat(model.getAttribute("componentVersion")).isEqualTo("4.17.15");
        assertThat(model.getAttribute("projectsCount")).isEqualTo(3L);
        assertThat(model.getAttribute("dependencyPaths")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("scan version이 null이면 '-'가 모델에 담긴다")
    void populateModel_nullScanVersion_showsDash() {
        Project project = Project.builder().id(1L).name("P").build();

        Library lib = Library.builder()
                .id(10L)
                .name("lib")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();

        ScanResult scan = ScanResult.builder().id(5L).version(null).status(ScanStatus.COMPLETED).build();

        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getId()).thenReturn(20L);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.getScanResult()).thenReturn(scan);
        when(sc.isReviewed()).thenReturn(false);
        when(sc.getDependencyInfo()).thenReturn(null);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        assertThat(model.getAttribute("projectVersion")).isEqualTo("-");
        assertThat(model.getAttribute("dependencyInfo")).isEqualTo("-");
    }

    @Test
    void populateModel_preservedAssessment_usesHistoricalEvidenceAfterSharedLibraryChanges() throws Exception {
        Project project = Project.builder().id(1L).name("P").build();
        Cve currentCve = Cve.builder().id(77L).cveId("CVE-1").title("current title").build();
        Library library = Library.builder().id(10L).name("live-name").version("9.0").ecosystem("NPM")
                .licenseName("GPL-3.0").licenseStatus(LicenseStatus.RESTRICTED).malicious(false)
                .cves(List.of(currentCve)).build();
        ScanResult scan = ScanResult.builder().id(5L).version("1").status(ScanStatus.COMPLETED)
                .assessmentJson(preservedJson(10L, "historical-name", "1.0", "MIT", "historical title", true)).build();
        ScanComponent component = ScanComponent.builder().id(20L).library(library).scanResult(scan).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        assertThat(model.getAttribute("preservedAssessment")).isEqualTo(true);
        assertThat(model.getAttribute("componentName")).isEqualTo("historical-name");
        assertThat(model.getAttribute("componentVersion")).isEqualTo("1.0");
        assertThat(model.getAttribute("licenseName")).isEqualTo("MIT");
        assertThat(model.getAttribute("malicious")).isEqualTo(true);
        assertThat(model.getAttribute("vulnerabilityLookupOutcomes")).isEqualTo(Map.of("OSV", "RESOLVED"));
        assertThat(((List<?>) model.getAttribute("cves"))).hasSize(1);
        assertThat(((com.salkcoding.oswl.dto.CveDto) ((List<?>) model.getAttribute("cves")).get(0)).getTitle())
                .isEqualTo("historical title");
        assertThat(((com.salkcoding.oswl.dto.CveDto) ((List<?>) model.getAttribute("cves")).get(0)).getCveDbId())
                .isNull();
    }

    @Test
    void populateModel_preservedAssessment_missingLibraryOrMalformedTimestamp_failsClosed() throws Exception {
        Project project = Project.builder().id(1L).name("P").build();
        Library library = Library.builder().id(10L).name("live").version("1").cves(List.of()).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        ScanResult malformed = ScanResult.builder().id(5L).version("1").status(ScanStatus.COMPLETED)
                .assessmentJson(preservedJson(10L, "live", "1", "MIT", "title", false).replace("2026-01-01T00:00:00Z", "bad-time"))
                .build();
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L))
                .thenReturn(Optional.of(ScanComponent.builder().id(20L).library(library).scanResult(malformed).build()));
        assertThatThrownBy(() -> componentDetailService.populateModel(1L, 20L, new ConcurrentModel()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timestamp");

        ScanResult missing = ScanResult.builder().id(6L).version("1").status(ScanStatus.COMPLETED)
                .assessmentJson(preservedJson(99L, "other", "1", "MIT", "title", false)).build();
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L))
                .thenReturn(Optional.of(ScanComponent.builder().id(20L).library(library).scanResult(missing).build()));
        assertThatThrownBy(() -> componentDetailService.populateModel(1L, 20L, new ConcurrentModel()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not contain library");
    }

    @Test
    void populateModel_preservedFindingDoesNotReplaceLivePrTargetOrInferCompleteness() throws Exception {
        Project project = Project.builder().id(1L).name("P").build();
        Library library = Library.builder().id(10L).name("live").version("1").ecosystem("NPM")
                .latestVersion("CURRENT").isLatestVersion(false).cves(List.of()).build();
        ScanResult scan = ScanResult.builder().id(5L).version("1").status(ScanStatus.COMPLETED)
                .assessmentJson(preservedJson(10L, "historical", "1", "MIT", "old", false, "OLD", false)).build();
        ScanComponent component = ScanComponent.builder().id(20L).library(library).scanResult(scan).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        assertThat(model.getAttribute("prTargetVersion")).isEqualTo("CURRENT");
        assertThat(model.getAttribute("vulnerabilitiesAnalyzed")).isEqualTo(false);
        assertThat(((com.salkcoding.oswl.dto.CveDto) ((List<?>) model.getAttribute("cves")).get(0)).getFixVersion())
                .isEqualTo("OLD");
    }

    @Test
    void populateModel_preservedMatchingFindingRetainsCurrentCommentaryAndVectorScore() throws Exception {
        var project = Project.builder().id(1L).name("P").build();
        var cve = Cve.builder().id(77L).cveId("CVE-2026-123450").title("Same evidence")
                .severity(com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL)
                .cvss3Vector("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")
                .aiSummary("Current commentary").build();
        var library = Library.builder().id(10L).name("fixture").version("1.0").cves(List.of(cve)).build();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.salkcoding.oswl.dto.scan.ScanAssessment(1, "2026-01-01T00:00:00Z",
                        List.of(ScanAssessmentService.fromLibrary(library))));
        var scan = ScanResult.builder().id(5L).version("1.0").assessmentJson(json).build();
        var component = ScanComponent.builder().id(20L).library(library).scanResult(scan).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(component));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);
        var model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);
        var dto = (com.salkcoding.oswl.dto.CveDto) ((List<?>) model.getAttribute("cves")).getFirst();
        assertThat(dto.getAiSummary()).isEqualTo("Current commentary");
        assertThat(dto.getCveDbId()).isEqualTo(77L);
        assertThat(dto.getCvssScore()).isEqualTo(9.8);
        assertThat(cve.getCvssScore()).isNull();
        assertThat(library.getCves()).containsExactly(cve);
    }

    private String preservedJson(Long libraryId, String name, String version, String license,
                                 String title, boolean malicious) throws Exception {
        return preservedJson(libraryId, name, version, license, title, malicious, null, true);
    }

    private String preservedJson(Long libraryId, String name, String version, String license,
                                 String title, boolean malicious, String fixVersion,
                                 boolean lookupTimesVerified) throws Exception {
        var finding = new com.salkcoding.oswl.dto.scan.ScanAssessment.Finding(
                "CVE-1", null, com.salkcoding.oswl.domain.enums.RiskLevel.HIGH, 8.0, null,
                title, "summary", null, fixVersion, java.util.Set.of(), null, null,
                java.util.Set.of(), false, null, null);
        var assessment = new com.salkcoding.oswl.dto.scan.ScanAssessment(1, "2026-01-01T00:00:00Z",
                List.of(new com.salkcoding.oswl.dto.scan.ScanAssessment.LibraryAssessment(libraryId, name, version,
                        "NPM", license, List.of(license), LicenseStatus.PERMITTED, "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:00Z", malicious, Map.of("OSV", "RESOLVED"), null,
                        List.of(finding), lookupTimesVerified)));
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(assessment);
    }

    // ── defer ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("defer: 컴포넌트가 없으면 예외를 던진다")
    void defer_throwsWhenComponentNotFound() {
        when(scanComponentRepository.findByIdAndProjectIdWithCves(99L, 1L)).thenReturn(Optional.empty());

        DeferralRequest req = buildDeferralRequest("temporary", "1-month", "project");

        assertThatThrownBy(() -> componentDetailService.defer(1L, 99L, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("defer: scope=project 일 때 단건 감사 로그가 기록된다")
    void defer_singleScope_logsComponent() {
        Library lib = Library.builder().id(10L).name("lodash").version("4.17.15").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = buildDeferralRequest("temporary", "1-month", "project");
        componentDetailService.defer(1L, 20L, req);

        verify(auditLogService).log(eq("COMPONENT.DEFER"), eq("COMPONENT"), eq("20"), any(), any());
        verify(auditLogService, never()).log(eq("COMPONENT.DEFER_ALL"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("defer: scope=all-projects 일 때 접근 가능한 프로젝트에만 적용하고 전체 감사 로그가 기록된다")
    void defer_allScope_logsLibrary() {
        Library lib = Library.builder().id(10L).name("lodash").version("4.17.15").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);

        ScanComponent other = mock(ScanComponent.class);

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of(1L, 2L));
        when(scanComponentRepository.findAllByLibraryIdAndProjectIdIn(10L, List.of(1L, 2L)))
                .thenReturn(List.of(sc, other));

        DeferralRequest req = buildDeferralRequest("temporary", "1-month", "all-projects");
        componentDetailService.defer(1L, 20L, req);

        // Cross-project propagation is restricted to projects the current user can access
        verify(scanComponentRepository).findAllByLibraryIdAndProjectIdIn(10L, List.of(1L, 2L));
        verify(other).applyDeferral(eq("temporary"), any(), any(), anyString());
        verify(auditLogService).log(eq("COMPONENT.DEFER_ALL"), eq("LIBRARY"), eq("10"), any(),
                argThat(d -> d.contains("applied=2")));
    }

    @Test
    @DisplayName("defer: expiry=indefinite 이면 만료 없이 저장된다")
    void defer_indefiniteExpiry() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = buildDeferralRequest("wont-fix", "indefinite", "project");
        componentDetailService.defer(1L, 20L, req);

        // applyDeferral called with null expiry
        verify(sc).applyDeferral(eq("wont-fix"), isNull(), any(), anyString());
    }

    @Test
    @DisplayName("defer: reason=other 이면 otherText가 reason 코드에 포함된다")
    void defer_otherReason_includesText() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        // Build a DeferralRequest with reason=other and otherText
        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", "other");
        setField(req, "otherText", "custom reason text");
        setField(req, "expiry", "indefinite");
        setField(req, "scope", "project");

        componentDetailService.defer(1L, 20L, req);

        verify(sc).applyDeferral(argThat(r -> r.startsWith("other:custom")), isNull(), any(), anyString());
    }

    @Test
    @DisplayName("defer: reason=other 이고 otherText가 길어도 reason 코드는 50자를 넘지 않는다")
    void defer_otherReason_truncatedToColumnLength() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", "other");
        setField(req, "otherText", "x".repeat(200));
        setField(req, "expiry", "indefinite");
        setField(req, "scope", "project");

        componentDetailService.defer(1L, 20L, req);

        // deferral_reason is varchar(50): final reason code must fit the column
        verify(sc).applyDeferral(argThat(r -> r != null && r.length() <= 50 && r.startsWith("other:")),
                isNull(), any(), anyString());
    }

    @Test
    @DisplayName("defer: scope=all-projects 인데 접근 가능 프로젝트가 없으면 현재 컴포넌트만 적용된다")
    void defer_allScope_noAccessibleProjects_appliesCurrentOnly() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of());

        DeferralRequest req = buildDeferralRequest("temporary", "1-month", "all-projects");
        componentDetailService.defer(1L, 20L, req);

        verify(scanComponentRepository, never()).findAllByLibraryIdAndProjectIdIn(any(), any());
        verify(auditLogService).log(eq("COMPONENT.DEFER_ALL"), eq("LIBRARY"), eq("10"), any(),
                argThat(d -> d.contains("applied=1")));
    }

    // ── resolveExpiryDate (via defer) ─────────────────────────────────────

    @Test
    @DisplayName("expiry=1-week 이면 약 7일 후 날짜가 설정된다")
    void defer_oneWeekExpiry() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = buildDeferralRequest("temporary", "1-week", "project");
        componentDetailService.defer(1L, 20L, req);

        verify(sc).applyDeferral(any(), argThat(dt -> dt != null && dt.isAfter(java.time.LocalDateTime.now().plusDays(6))), any(), anyString());
    }

    @Test
    @DisplayName("expiry=custom, customDate 유효하면 해당 날짜로 설정된다")
    void defer_customExpiry_validDate() {
        Library lib = Library.builder().id(10L).name("lib").version("1.0").build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", "temporary");
        setField(req, "expiry", "custom");
        setField(req, "customDate", "2099-12-31");
        setField(req, "scope", "project");

        componentDetailService.defer(1L, 20L, req);

        verify(sc).applyDeferral(any(),
                argThat(dt -> dt != null && dt.getYear() == 2099),
                any(), anyString());
    }

    @Test
    @DisplayName("expiry=custom, customDate 잘못된 형식이면 InvalidRequestException을 던진다")
    void defer_customExpiry_invalidDate_rejected() {
        ScanComponent sc = mock(ScanComponent.class);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", "temporary");
        setField(req, "expiry", "custom");
        setField(req, "customDate", "not-a-date");
        setField(req, "scope", "project");

        assertThatThrownBy(() -> componentDetailService.defer(1L, 20L, req))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        verify(sc, org.mockito.Mockito.never()).applyDeferral(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("expiry=custom, customDate 과거 날짜이면 InvalidRequestException을 던진다")
    void defer_customExpiry_pastDate_rejected() {
        ScanComponent sc = mock(ScanComponent.class);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));

        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", "temporary");
        setField(req, "expiry", "custom");
        setField(req, "customDate", "2020-01-01");
        setField(req, "scope", "project");

        assertThatThrownBy(() -> componentDetailService.defer(1L, 20L, req))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        verify(sc, org.mockito.Mockito.never()).applyDeferral(any(), any(), any(), anyString());
    }
    // ── createPullRequest ─────────────────────────────────────────────────

    @Test
    @DisplayName("createPR: 컴포넌트가 없으면 IllegalArgumentException을 던진다")
    void createPullRequest_componentNotFound_throws() {
        when(scanComponentRepository.findByIdAndProjectIdWithCves(99L, 1L)).thenReturn(Optional.empty());

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 99L, req, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("createPR: 프로젝트가 없으면 IllegalArgumentException을 던진다")
    void createPullRequest_projectNotFound_throws() {
        ScanComponent sc = mock(ScanComponent.class);
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, req, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1");
    }

    @Test
    @DisplayName("createPR: VCS provider가 없으면 IllegalStateException을 던진다")
    void createPullRequest_noVcsProvider_throws() {
        ScanComponent sc = mock(ScanComponent.class);
        Project project = Project.builder().id(1L).name("P").vcsProvider(null).githubRepo(null).build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, req, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLI");
    }

    @Test
    @DisplayName("createPR: repoPath에 '/'가 없으면 IllegalStateException을 던진다")
    void createPullRequest_noRepoPath_throws() {
        ScanComponent sc = mock(ScanComponent.class);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITHUB).githubRepo(null).build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, req, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VCS repository");
    }

    @Test
    @DisplayName("createPR: GITHUB provider이지만 githubToken이 없으면 IllegalStateException을 던진다")
    void createPullRequest_github_noToken_throws() {
        Library lib = libraryForPrBump();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITHUB).githubRepo("owner/repo").build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, req, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No GitHub account is connected");
    }

    @Test
    @DisplayName("createPR: GITHUB 성공 경로 — PR URL과 번호가 반환된다")
    void createPullRequest_github_success() {
        Library lib = libraryForPrBump();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITHUB).githubRepo("owner/repo").build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(gitHubService.createVersionBumpPr(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("prUrl", "https://github.com/owner/repo/pull/42", "prNumber", 42));
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any());

        CreatePrRequest req = buildCreatePrRequest("main", List.of("reviewer1"), "Bump for security fix");
        Map<String, Object> result = componentDetailService.createPullRequest(1L, 20L, req, 1L, "gh-token");

        assertThat(result).containsEntry("prUrl", "https://github.com/owner/repo/pull/42");
        assertThat(result).containsEntry("prNumber", 42);
        verify(gitHubService).createVersionBumpPr(
                eq("gh-token"), eq("owner"), eq("repo"), eq("main"),
                eq("lodash"), any(), any(), any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("createPR: GITLAB 연결이 없으면 IllegalStateException을 던진다")
    void createPullRequest_gitlab_noConnection_throws() {
        Library lib = libraryForPrBump();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITLAB).githubRepo("group/project").build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(2L, VcsProvider.GITLAB))
                .thenReturn(Optional.empty());

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        assertThatThrownBy(() -> componentDetailService.createPullRequest(1L, 20L, req, 2L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitLab");
    }

    @Test
    @DisplayName("createPR: GITLAB 성공 경로 — MR URL과 번호가 반환된다")
    void createPullRequest_gitlab_success() {
        Library lib = libraryForPrBump();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.GITLAB).githubRepo("group/project").build();

        UserVcsConnection conn = UserVcsConnection.builder()
                .id(5L).provider(VcsProvider.GITLAB).active(true)
                .accessTokenEncrypted("enc-token").serverUrl("https://gitlab.example.com").build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(2L, VcsProvider.GITLAB))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("enc-token")).thenReturn("plain-token");
        when(gitLabService.createVersionBumpMr(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("prUrl", "https://gitlab.example.com/group/project/-/merge_requests/7", "prNumber", 7));
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any());

        CreatePrRequest req = buildCreatePrRequest("develop", null, null);
        Map<String, Object> result = componentDetailService.createPullRequest(1L, 20L, req, 2L, null);

        assertThat(result).containsEntry("prNumber", 7);
        verify(gitLabService).createVersionBumpMr(
                eq("plain-token"), eq("https://gitlab.example.com"), eq("group/project"), eq("develop"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createPR: BITBUCKET 성공 경로 — PR URL과 번호가 반환된다")
    void createPullRequest_bitbucket_success() {
        Library lib = libraryForPrBump();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        Project project = Project.builder().id(1L).name("P")
                .vcsProvider(VcsProvider.BITBUCKET).githubRepo("workspace/myrepo").build();

        UserVcsConnection conn = UserVcsConnection.builder()
                .id(6L).provider(VcsProvider.BITBUCKET).active(true)
                .accessTokenEncrypted("enc-bb-token").vcsUsername("bbuser")
                .serverUrl("https://bitbucket.org").build();

        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(3L, VcsProvider.BITBUCKET))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("enc-bb-token")).thenReturn("plain-bb-token");
        when(bitbucketService.createVersionBumpPr(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("prUrl", "https://bitbucket.org/workspace/myrepo/pull-requests/3", "prNumber", 3));
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any());

        CreatePrRequest req = buildCreatePrRequest("main", null, null);
        Map<String, Object> result = componentDetailService.createPullRequest(1L, 20L, req, 3L, null);

        assertThat(result).containsEntry("prNumber", 3);
        verify(bitbucketService).createVersionBumpPr(
                eq("plain-bb-token"), eq("bbuser"), eq("https://bitbucket.org"),
                eq("workspace/myrepo"), eq("main"), any(), any(), any(), any(), any(), any());
    }

    // ── licenseRiskLabel (via populateModel) ──────────────────────────────

    @Test
    @DisplayName("licenseRiskLabel: RESTRICTED 라이선스는 'Restricted'를 표시한다")
    void populateModel_licenseRiskLabel_restricted() {
        Library lib = Library.builder().id(10L).name("gpl").version("3.0")
                .licenseStatus(LicenseStatus.RESTRICTED).cves(List.of()).build();
        Model model = buildAndPopulateModel(lib);

        assertThat(model.getAttribute("licenseRiskLabel")).isEqualTo("Restricted");
    }

    @Test
    @DisplayName("licenseRiskLabel: CAUTION 라이선스는 'Caution'을 표시한다")
    void populateModel_licenseRiskLabel_caution() {
        Library lib = Library.builder().id(10L).name("lgpl").version("2.1")
                .licenseStatus(LicenseStatus.CAUTION).cves(List.of()).build();
        Model model = buildAndPopulateModel(lib);

        assertThat(model.getAttribute("licenseRiskLabel")).isEqualTo("Caution");
    }

    @Test
    @DisplayName("licenseRiskLabel: UNKNOWN + licenseName 있으면 'Non-standard'를 표시한다")
    void populateModel_licenseRiskLabel_nonStandard() {
        Library lib = Library.builder().id(10L).name("custom").version("1.0")
                .licenseStatus(LicenseStatus.UNKNOWN).licenseName("Custom-Proprietary")
                .cves(List.of()).build();
        Model model = buildAndPopulateModel(lib);

        assertThat(model.getAttribute("licenseRiskLabel")).isEqualTo("Non-standard");
        assertThat((Boolean) model.getAttribute("licenseIsNonStandard")).isTrue();
    }

    @Test
    @DisplayName("licenseRiskLabel: UNKNOWN + licenseName 없으면 'Unknown'을 표시한다")
    void populateModel_licenseRiskLabel_unknown() {
        Library lib = Library.builder().id(10L).name("unk").version("1.0")
                .licenseStatus(LicenseStatus.UNKNOWN).cves(List.of()).build();
        Model model = buildAndPopulateModel(lib);

        assertThat(model.getAttribute("licenseRiskLabel")).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("licenseRiskLabel: PERMITTED 라이선스는 'Permitted'를 표시한다")
    void populateModel_licenseRiskLabel_permitted() {
        Library lib = Library.builder().id(10L).name("mit").version("1.0")
                .licenseStatus(LicenseStatus.PERMITTED).cves(List.of()).build();
        Model model = buildAndPopulateModel(lib);

        assertThat(model.getAttribute("licenseRiskLabel")).isEqualTo("Permitted");
    }

    // ── deriveShortName / toPathDto (via populateModel with real dependency paths) ──

    @Test
    @DisplayName("buildPathDtos: Maven groupId:artifactId 형태의 이름을 shortName으로 축약한다")
    void populateModel_pathNodes_derivesShortNameForColon() {
        DependencyPath path = buildPath(2,
                new DependencyPath.PathNode("", "1.0"),     // blank name → use project name
                new DependencyPath.PathNode("org.spring:spring-core", "5.0"));
        Library lib = Library.builder().id(10L).name("lib").licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of()).build();

        Project project = Project.builder().id(1L).name("MyProject").build();
        ScanResult scan = ScanResult.builder().id(5L).version("1.0").status(ScanStatus.COMPLETED).build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getId()).thenReturn(20L);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.getScanResult()).thenReturn(scan);
        when(sc.isReviewed()).thenReturn(false);
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L))
                .thenReturn(List.of(path));

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        @SuppressWarnings("unchecked")
        var paths = (List<com.salkcoding.oswl.dto.DependencyPathDto>) model.getAttribute("dependencyPaths");
        assertThat(paths).hasSize(1);
        var nodes = paths.getFirst().getNodes();
        // Second node: "org.spring:spring-core" → shortName should be "spring-core"
        assertThat(nodes.get(1).getShortName()).isEqualTo("spring-core");
        // Root node with blank name → use project name
        assertThat(nodes.getFirst().getShortName()).isEqualTo("MyProject");
    }

    @Test
    @DisplayName("buildPathDtos: 슬래시 포함 이름은 마지막 세그먼트를 shortName으로 사용한다")
    void populateModel_pathNodes_derivesShortNameForSlash() {
        DependencyPath path = buildPath(2,
                new DependencyPath.PathNode("", ""),
                new DependencyPath.PathNode("github.com/user/mylib", "v1.2"));
        Library lib = Library.builder().id(10L).name("lib").licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of()).build();

        Project project = Project.builder().id(1L).name("P").build();
        ScanResult scan = ScanResult.builder().id(5L).version("1.0").status(ScanStatus.COMPLETED).build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getId()).thenReturn(20L);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.getScanResult()).thenReturn(scan);
        when(sc.isReviewed()).thenReturn(false);
        when(scanComponentRepository.countDistinctProjectsByLibraryId(10L)).thenReturn(1L);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L))
                .thenReturn(List.of(path));

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);

        @SuppressWarnings("unchecked")
        var paths = (List<com.salkcoding.oswl.dto.DependencyPathDto>) model.getAttribute("dependencyPaths");
        var nodes = paths.getFirst().getNodes();
        assertThat(nodes.get(1).getShortName()).isEqualTo("mylib");
    }

    // ── helper ────────────────────────────────────────────────────────────

    private Model buildAndPopulateModel(Library lib) {
        Project project = Project.builder().id(1L).name("P").build();
        ScanResult scan = ScanResult.builder().id(5L).version("1.0").status(ScanStatus.COMPLETED).build();
        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getId()).thenReturn(20L);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.getScanResult()).thenReturn(scan);
        when(sc.isReviewed()).thenReturn(false);
        when(scanComponentRepository.countDistinctProjectsByLibraryId(lib.getId())).thenReturn(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanComponentRepository.findByIdAndProjectIdWithCves(20L, 1L)).thenReturn(Optional.of(sc));
        when(dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(20L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        componentDetailService.populateModel(1L, 20L, model);
        return model;
    }

    private DependencyPath buildPath(int depth, DependencyPath.PathNode... nodes) {
        return DependencyPath.builder()
                .pathIndex(0)
                .depth(depth)
                .pathNodes(List.of(nodes))
                .build();
    }

    private DeferralRequest buildDeferralRequest(String reason, String expiry, String scope) {
        DeferralRequest req = new DeferralRequest();
        setField(req, "reason", reason);
        setField(req, "expiry", expiry);
        setField(req, "scope", scope);
        return req;
    }

    private Library libraryForPrBump() {
        return Library.builder()
                .id(10L).name("lodash").version("4.17.15")
                .latestVersion("4.17.21").isLatestVersion(false)
                .cves(List.of())
                .build();
    }

    private CreatePrRequest buildCreatePrRequest(String targetBranch, List<String> reviewers, String description) {
        CreatePrRequest req = new CreatePrRequest();
        setField(req, "targetBranch", targetBranch);
        setField(req, "reviewers", reviewers);
        setField(req, "prDescription", description);
        return req;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
