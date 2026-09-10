package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RemediationTargetUiTest extends UiTestBase {
    private final com.salkcoding.oswl.repository.project.ProjectRepository projects;
    private final com.salkcoding.oswl.repository.scan.ScanResultRepository scans;
    private final com.salkcoding.oswl.repository.scan.ScanComponentRepository components;
    private final com.salkcoding.oswl.repository.vulnerability.LibraryRepository libraries;
    private final com.salkcoding.oswl.repository.vulnerability.CveRepository cves;
    private final org.springframework.context.MessageSource messages;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean OsvClient osv;
    @MockitoBean com.salkcoding.oswl.service.vcs.GitHubService github;
    @MockitoBean com.salkcoding.oswl.service.vcs.GitLabService gitlab;
    @MockitoBean com.salkcoding.oswl.service.vcs.BitbucketService bitbucket;

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko", "ja"})
    void incompleteTargetShowsLocalizedErrorWithoutCreatingPr(String language) throws Exception {
        when(osv.queryBatch(any())).thenReturn(List.of(OsvClient.OsvResult.unresolved(), OsvClient.OsvResult.unresolved()));
        var project = projects.save(Project.builder().name("Unverified target fixture")
                .vcsProvider(com.salkcoding.oswl.auth.enums.VcsProvider.GITHUB).githubRepo("fixture/repo-" + language).build());
        var scan = scans.save(ScanResult.builder().project(project).version("main").status(ScanStatus.COMPLETED).build());
        var library = Library.builder().name("unverified-target-" + language).version("1.0.0").ecosystem("NPM")
                .licenseStatus(LicenseStatus.UNKNOWN).build();
        library.markFetched();
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", Map.of("OSV-fixture", "2026-01-01T00:00:00Z"),
                Set.of("OSV-fixture", "CVE-2026-0001"), java.time.Instant.now().plusSeconds(3600));
        library = libraries.save(library);
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        cves.save(Cve.builder().library(library).ghsaId("OSV-fixture").cveId("CVE-2026-0001").fixVersion("2.0.0").severity(RiskLevel.HIGH).build());
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=" + language));
        String label = messages.getMessage("componentDetail.btn.applyPatch", null, Locale.forLanguageTag(language));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label).setExact(true)).first().click();
        var response = page.waitForResponse(r -> r.url().endsWith("/create-pr"), () ->
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(
                        messages.getMessage("componentDetail.patch.btn.create", null, Locale.forLanguageTag(language))).setExact(true)).click());
        assertThat(response.status()).isEqualTo(400);
        var error = page.locator("[x-text='prError']");
        assertThat(error).hasText(messages.getMessage("componentDetail.patch.targetUnverified", null, Locale.forLanguageTag(language)));
        assertThat(error).isVisible();
        assertThat(page.getByText(messages.getMessage("componentDetail.patch.label.currentFindings", null,
                Locale.forLanguageTag(language)), new Page.GetByTextOptions().setExact(true))).isVisible();
        verify(osv).queryBatch(any());
        verifyNoInteractions(github, gitlab, bitbucket);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND target_id = ?", Long.class,
                "COMPONENT.CREATE_PR_WITHHELD", component.getId().toString())).isEqualTo(1L);
        Path output = Path.of("build/reports/remediation-target-ui/target-unverified-" + language + ".png");
        Files.createDirectories(output.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true)
                .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED));
    }
}
