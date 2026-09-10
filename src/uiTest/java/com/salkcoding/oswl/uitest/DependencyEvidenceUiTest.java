package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Page;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DependencyEvidenceUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final LibraryRepository libraries;
    private final ScanComponentRepository components;
    private final com.salkcoding.oswl.repository.vulnerability.CveRepository cves;

    @Test void unknownSecurityFixDoesNotOfferLatestAsPatchPr() throws Exception {
        var project = projects.save(Project.builder().name("Unknown fix fixture")
                .vcsProvider(com.salkcoding.oswl.auth.enums.VcsProvider.GITHUB).githubRepo("fixture/repo").build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = libraries.save(Library.builder().name("unknown-fix-fixture").version("1.0.0")
                .ecosystem("NUGET").latestVersion("9.0.0").isLatestVersion(false)
                .licenseStatus(LicenseStatus.UNKNOWN).build());
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        cves.save(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .cveId("CVE-2026-0002").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH).build());
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=en"));
        assertThat(page.locator("#component-detail-content").innerText()).contains("CVE-2026-0002", "9.0.0");
        assertThat(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Apply Patch (Create PR)")).count()).isZero();
        Path output = Path.of("build/reports/dependency-evidence-ui/unknown-fix-pr.png");
        Files.createDirectories(output.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "zero", "vector"})
    void unscoredAdvisoryKeepsItsPatchabilityInTheRenderedDetail(String scoreState) throws Exception {
        var project = projects.save(Project.builder().name("Unscored patch fixture").build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = libraries.save(Library.builder().name("unscored-patch-fixture-" + scoreState).version("1.0.0")
                .ecosystem("NUGET").licenseStatus(LicenseStatus.UNKNOWN).build());
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        cves.save(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .cveId("CVE-2026-0001").severity(null).fixVersion("2.0.0")
                .cvssScore(scoreState.equals("zero") ? 0.0 : null)
                .cvss3Vector(scoreState.equals("vector") ? "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H" : null).build());
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=en"));
        String body = page.locator("#component-detail-content").innerText();
        assertThat(body).contains("Patchable", "CVE-2026-0001", "2.0.0", "Unscored");
        assertThat(body).doesNotContain("No security vulnerabilities detected", "Non-Patchable");
        if (scoreState.equals("missing")) {
            assertThat(body).doesNotContain("CVSS 0.0").contains("CVSS Unknown");
        } else {
            assertThat(body).contains(scoreState.equals("zero") ? "CVSS 0.0" : "CVSS 9.8");
        }
        Path output = Path.of("build/reports/dependency-evidence-ui/unscored-patch-" + scoreState + ".png");
        Files.createDirectories(output.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(output).setFullPage(true));
    }

    @Test void longEvidenceStaysWithinItsContainerAndRemainsReadableInDetail() throws Exception {
        String evidence = ("NuGet lock source=\"project/" + "긴경로LongPath".repeat(25)
                + "/packages.lock.json\" target=\"net8.0/win-x64\" type=\"Direct\"\n").repeat(5).strip();
        var project = projects.save(Project.builder().name("Dependency evidence fixture").build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = libraries.save(Library.builder().name("evidence-fixture").version("1.0.0")
                .ecosystem("NUGET").licenseStatus(LicenseStatus.UNKNOWN).build());
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).dependencyInfo(evidence).build());
        loginAsTestAdmin();
        Path output = Path.of("build/reports/dependency-evidence-ui");
        Files.createDirectories(output);
        for (int width : new int[]{1440, 1024}) {
            page.setViewportSize(width, 1000);
            page.navigate(url("/projects/" + project.getId() + "/security-center?lang=ko"));
            var summary = page.locator(".component-row p").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("NuGet lock"));
            assertThat(summary.count()).isEqualTo(1);
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("list-" + width + ".png")).setFullPage(true));
            assertThat((Boolean) summary.evaluate("e => e.getBoundingClientRect().right <= e.parentElement.getBoundingClientRect().right + 1"))
                    .as("summary within its column at %s", width).isTrue();
            // The list appends its project-count summary to the original evidence.
            assertThat(summary.textContent()).startsWith(evidence);
            page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=ko"));
            var detail = page.locator("span").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("NuGet lock")).last();
            detail.scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("detail-" + width + ".png")).setFullPage(true));
            assertThat(detail.textContent()).isEqualTo(evidence);
            assertThat((Boolean) detail.evaluate("e => e.getBoundingClientRect().right <= e.parentElement.getBoundingClientRect().right + 1"))
                    .as("detail within its container at %s", width).isTrue();
            assertThat((Boolean) detail.evaluate("e => e.scrollHeight <= e.clientHeight + 1"))
                    .as("full detail is not clipped").isTrue();
        }
    }
}
