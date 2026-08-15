package com.salkcoding.oswl.uitest;

import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanFinding;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Reachability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * axe-core accessibility audit across the app's main screens (ROADMAP v1.0.5.1 — C1-2), on top
 * of the color-contrast token fixes from C1-1. Asserts zero serious/critical violations — the
 * DoD's "axe 심각 이슈 0건" — while leaving moderate/minor findings in the per-page report under
 * {@code build/reports/axe/} for follow-up (e.g. the chart-as-table alternative for
 * org-dashboard/risk-trend, tracked separately since it's a feature addition, not a fix).
 */
@DisplayName("axe audit: login, projects, security center, settings, org dashboard, component detail, version diff")
class AxeAuditUiTest extends UiTestBase {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private com.salkcoding.oswl.repository.vulnerability.LibraryRepository libraryRepository;

    @Test
    @DisplayName("No serious/critical axe violations on the app's main screens")
    void mainScreensHaveNoSeriousAxeViolations() throws IOException {
        Project project = seedProjectWithScan("UiTest-Axe-Project");
        Long componentId = project.getScanResults().get(0).getComponents().get(0).getId();
        Long projectId = project.getId();

        List<String> failures = new ArrayList<>();

        // Unauthenticated: login page itself
        page.navigate(url("/login"));
        page.waitForLoadState();
        assertSeriousViolationsAreZero("login", runAxeScan(), failures);

        loginAsTestAdmin();

        assertPage(url("/projects"), "projects", failures);
        assertPage(url("/projects/" + projectId + "/security-center"), "security-center", failures);
        assertFindingsSectionRendersRealData();
        assertPage(url("/settings"), "settings", failures);
        assertPage(url("/org-dashboard"), "org-dashboard", failures);
        assertPage(url("/projects/" + projectId + "/version-diff"), "version-diff", failures);
        assertPage(url("/projects/" + projectId + "/components/" + componentId), "component-detail", failures);
        assertReachabilityEvidenceSectionRendersRealData();

        assertThat(failures)
                .withFailMessage("Serious/critical axe violations found:%n%s", String.join("\n", failures))
                .isEmpty();
    }

    /**
     * axe only proves the Findings table is structurally valid (real table, no ARIA issues) —
     * it says nothing about whether the two seeded findings actually made it through
     * SecurityCenterService -> the th:switch severity badges -> the rendered page. Assert the
     * actual page content instead of trusting "axe passed" to mean "the feature works".
     */
    private void assertFindingsSectionRendersRealData() {
        String body = page.locator("body").innerText();
        assertThat(body).contains("generic-api-key");
        assertThat(body).contains("tf-public-s3");
        assertThat(body).contains("config/settings.py:42");
        assertThat(body).contains("infra/main.tf:10");
    }

    /**
     * axe only proves the reachability evidence section is structurally valid — it says nothing
     * about whether a REACHABLE verdict and its evidence lines actually made it through
     * ComponentDetailService -> the Thymeleaf th:if gate -> the rendered page.
     */
    private void assertReachabilityEvidenceSectionRendersRealData() {
        String body = page.locator("body").innerText();
        assertThat(body).contains("com.example.UiTestCaller");
        assertThat(body).contains("UiTest.Axe.Project.lib.Vulnerable");
    }

    private void assertPage(String targetUrl, String reportName, List<String> failures) throws IOException {
        page.navigate(targetUrl);
        page.waitForLoadState();
        assertSeriousViolationsAreZero(reportName, runAxeScan(), failures);
    }

    private void assertSeriousViolationsAreZero(String name, AxeResults results, List<String> failures) throws IOException {
        writeAxeReport(name, results);
        List<Rule> violations = results.getViolations();
        if (violations == null) {
            return;
        }
        for (Rule v : violations) {
            if ("serious".equals(v.getImpact()) || "critical".equals(v.getImpact())) {
                failures.add(name + ": [" + v.getImpact() + "] " + v.getId() + " (" +
                        (v.getNodes() != null ? v.getNodes().size() : 0) + " nodes)");
            }
        }
    }

    private Project seedProjectWithScan(String name) {
        Project project = projectRepository.save(Project.builder().name(name).build());

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.now());

        Library library = Library.builder()
                .name(name + "-lib").version("1.0.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .build();
        library.getCves().add(Cve.builder().library(library)
                .cveId(name + "-CVE-1").severity(RiskLevel.HIGH).cvssScore(7.5).build());
        library = libraryRepository.save(library);

        scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(library)
                .reachability(Reachability.REACHABLE)
                .reachabilityEvidence("com.example.UiTestCaller -> " + name.replace('-', '.') + ".lib.Vulnerable")
                .build());
        // A finding of each severity axe will actually render — exercises the Findings table's
        // severity-badge branches, not just its empty state.
        scan.getFindings().add(ScanFinding.builder().scanResult(scan).type(ScanFindingType.SECRET)
                .ruleId("generic-api-key").severity(RiskLevel.CRITICAL)
                .filePath("config/settings.py").lineNumber(42)
                .description("Possible API key literal").fingerprint("abc123def456").build());
        scan.getFindings().add(ScanFinding.builder().scanResult(scan).type(ScanFindingType.IAC)
                .ruleId("tf-public-s3").severity(RiskLevel.MEDIUM)
                .filePath("infra/main.tf").lineNumber(10)
                .description("S3 bucket may be publicly readable").build());
        project.getScanResults().add(scan);
        return projectRepository.save(project);
    }

    private void writeAxeReport(String name, AxeResults results) throws IOException {
        Path dir = Path.of("build", "reports", "axe");
        Files.createDirectories(dir);
        int violationCount = results.getViolations() != null ? results.getViolations().size() : 0;
        StringBuilder report = new StringBuilder();
        report.append("url: ").append(results.getUrl()).append('\n');
        report.append("violations: ").append(violationCount).append('\n');
        if (results.getViolations() != null) {
            results.getViolations().forEach(v -> {
                report.append(" - [").append(v.getImpact()).append("] ").append(v.getId())
                        .append(" (").append(v.getNodes() != null ? v.getNodes().size() : 0)
                        .append(" nodes): ").append(v.getDescription()).append('\n');
                if (v.getNodes() != null) {
                    v.getNodes().forEach(n -> report.append("     target=")
                            .append(n.getTarget()).append(" html=").append(n.getHtml()).append('\n'));
                }
            });
        }
        Files.writeString(dir.resolve(name + ".txt"), report.toString());
    }
}
