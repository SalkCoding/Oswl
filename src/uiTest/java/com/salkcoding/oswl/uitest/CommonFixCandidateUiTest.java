package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.TestConstructor;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommonFixCandidateUiTest extends UiTestBase {
    private final com.salkcoding.oswl.repository.project.ProjectRepository projects;
    private final com.salkcoding.oswl.repository.scan.ScanResultRepository scans;
    private final com.salkcoding.oswl.repository.scan.ScanComponentRepository components;
    private final com.salkcoding.oswl.repository.vulnerability.LibraryRepository libraries;
    private final com.salkcoding.oswl.repository.vulnerability.CveRepository cves;
    private final org.springframework.context.MessageSource messages;

    @ParameterizedTest
    @CsvSource({"en,current", "ko,current", "ja,current", "en,expired", "en,missing", "en,partial", "en,conflict", "en,uncovered"})
    void detailAndPrUseOnlyTheCommonCandidateAndPreserveIndividualFixes(String language, String state) throws Exception {
        var project = projects.save(Project.builder().name("Common candidate " + language + " " + state)
                .vcsProvider(com.salkcoding.oswl.auth.enums.VcsProvider.GITHUB).githubRepo("fixture/common-" + language + "-" + state).build());
        var scan = scans.save(ScanResult.builder().project(project).version("main").status(ScanStatus.COMPLETED).build());
        var library = Library.builder().name("common-" + language + "-" + state).version("1.0.0").ecosystem("NPM")
                .latestVersion("99.0.0").isLatestVersion(false).licenseStatus(LicenseStatus.UNKNOWN).build();
        library.markFetched();
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED", "GITHUB_ADVISORY", state.equals("partial") ? "UNAVAILABLE" : "NOT_CONFIGURED"));
        if (!state.equals("missing")) library.recordOsvFixAssessment("4.0.0", "SOURCE_FIXED_EVENT",
                Map.of("OSV-first", "2026-01-01T00:00:00Z", "OSV-second", "2026-01-01T00:00:00Z"),
                state.equals("uncovered") ? Set.of("OSV-first") : Set.of("OSV-first", "OSV-second"),
                Instant.now().plusSeconds(state.equals("expired") ? -60 : 3600));
        library = libraries.save(library);
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        cves.save(Cve.builder().library(library).ghsaId("OSV-first").cveId("CVE-2026-0001").fixVersion("2.0.0").severity(RiskLevel.CRITICAL).build());
        var second = Cve.builder().library(library).ghsaId("OSV-second").cveId("CVE-2026-0002").fixVersion("3.0.0").severity(RiskLevel.HIGH).build();
        if (state.equals("conflict")) second.withholdFixVersion(Set.of("3.0.0", "5.0.0"));
        cves.save(second);
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=" + language));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("CVE-2026-0001").setExact(true)).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("CVE-2026-0002").setExact(true)).click();
        var content = page.locator("#component-detail-content");
        assertThat(content.innerText()).contains("2.0.0", "CVE-2026-0001", "CVE-2026-0002");
        if (!state.equals("conflict")) assertThat(content.innerText()).contains("3.0.0");
        var locale = Locale.forLanguageTag(language);
        var button = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(
                messages.getMessage("componentDetail.btn.applyPatch", null, locale)).setExact(true));
        if (state.equals("current")) {
            assertThat(content.innerText()).contains("4.0.0", messages.getMessage("componentDetail.vuln.upgradeHint", null, locale));
            button.first().click();
            assertThat(page.getByText("v4.0.0", new Page.GetByTextOptions().setExact(true))).isVisible();
        } else {
            assertThat(content.innerText()).contains(messages.getMessage("componentDetail.vuln.noFix", null, locale)).doesNotContain("4.0.0");
            assertThat(button.count()).isZero();
        }
        var output = Path.of("build/reports/common-fix-candidate-ui/" + language + "-" + state + ".png");
        Files.createDirectories(output.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true)
                .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED));
        page.navigate(url("/projects/" + project.getId() + "/security-center?lang=" + language));
        assertThat(page.getByText(library.getName() + " " + library.getVersion(), new Page.GetByTextOptions().setExact(true))).isVisible();
        var recommendation = page.getByText("→ v4.0.0", new Page.GetByTextOptions().setExact(true));
        if (state.equals("current")) assertThat(recommendation).isVisible();
        else assertThat(recommendation.count()).isZero();
        assertThat(page.getByText("→ v2.0.0", new Page.GetByTextOptions().setExact(true)).count()).isZero();
    }
}
