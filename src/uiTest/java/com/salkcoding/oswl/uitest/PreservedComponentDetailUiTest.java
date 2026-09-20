package com.salkcoding.oswl.uitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.ScanAssessment;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.scan.ScanAssessmentService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PreservedComponentDetailUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final LibraryRepository libraries;
    private final CveRepository cves;
    private final ScanComponentRepository components;
    private final MessageSource messages;

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko", "ja"})
    void detailKeepsCapturedEvidenceAfterSharedFindingRemoval(String lang) throws Exception {
        var project = projects.save(Project.builder().name("Preserved detail " + lang).build());
        var at = LocalDateTime.now().minusHours(1);
        var library = libraries.save(Library.builder().name("historic-detail-" + lang).version("1.0.0")
                .ecosystem("NPM").licenseName("MIT").licenseStatus(LicenseStatus.PERMITTED)
                .fetchedAt(at).vulnerabilityLookupAt(at)
                .vulnerabilityLookupOutcomes(Map.of("OSV", "RESOLVED")).build());
        var old = cves.save(Cve.builder().library(library).cveId("CVE-2026-123450")
                .title("Captured advisory title").summary("Evidence captured before the shared finding was removed.")
                .severity(RiskLevel.HIGH).cvssScore(7.5).sources(Set.of(CveSource.OSV)).build());
        library.getCves().add(old);
        var candidate = cves.save(Cve.builder().library(library).cveId("CVE-2026-123451")
                .title("Unconfirmed CPE candidate").severity(RiskLevel.CRITICAL)
                .sources(Set.of(CveSource.NVD)).matchConfidence(MatchConfidence.LOW).build());
        library.getCves().add(candidate);
        var json = new ObjectMapper().writeValueAsString(new ScanAssessment(1, Instant.now().toString(),
                List.of(ScanAssessmentService.fromLibrary(library))));
        var scan = scans.save(ScanResult.builder().project(project).version("1.0")
                .status(ScanStatus.COMPLETED).assessmentJson(json).build());
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).dependencyInfo("Direct").build());

        cves.deleteById(old.getId());
        cves.deleteById(candidate.getId());
        library.getCves().clear();
        library.updateLicense("GPL-3.0", LicenseStatus.RESTRICTED);
        library.recordLookupOutcomes(Map.of("OSV", "UNAVAILABLE"));
        libraries.save(library);

        var errors = new java.util.ArrayList<String>();
        page.onPageError(errors::add);
        loginAsTestAdmin();
        page.setViewportSize(1280, 1000);
        var response = page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=" + lang));
        assertThat(response.status()).isEqualTo(200);
        var root = page.locator("#component-detail-content");
        assertThat(root.locator("[data-preserved-assessment]").innerText()).isEqualTo(
                messages.getMessage("componentDetail.assessment.preserved", null, Locale.forLanguageTag(lang)));
        assertThat(root.innerText()).contains("CVE-2026-123450", "Captured advisory title", "MIT").doesNotContain("GPL-3.0");
        assertThat(root.innerText()).doesNotContain(messages.getMessage("scanEvidence.currentDetail", null, Locale.forLanguageTag(lang)));
        assertThat(root.locator("[data-cve-db-id]").count()).isZero();
        assertThat(root.locator("[data-cpe-review]").count()).isEqualTo(1);
        assertThat(root.locator("[data-lookup-status]").innerText()).contains(
                messages.getMessage("componentDetail.lookup.RESOLVED", null, Locale.forLanguageTag(lang)));
        assertThat((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")).isTrue();
        var output = Path.of("build/reports/preserved-component-detail-ui");
        Files.createDirectories(output);
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(lang + ".png")).setFullPage(true));
        page.navigate(url("/projects/" + project.getId() + "/security-center?lang=" + lang));
        page.locator("a[data-comp-id='" + component.getId() + "']").click();
        var panel = page.locator("#slideout-content #component-detail-content");
        panel.locator("[data-preserved-assessment]").waitFor();
        assertThat(panel.innerText()).contains("Captured advisory title", "MIT").doesNotContain("GPL-3.0");
        assertThat(panel.locator("[data-cpe-review]").count()).isEqualTo(1);
        assertThat(panel.locator("[data-cve-db-id]").count()).isZero();
        page.waitForFunction("""
                () => {
                    const bounds = document.querySelector('#slideout-content #component-detail-content').getBoundingClientRect();
                    return bounds.left >= 0 && bounds.right <= window.innerWidth + 1;
                }
                """);
        assertThat((Boolean) panel.evaluate("e => e.scrollWidth <= e.clientWidth + 1")).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(lang + "-panel.png")));
        assertThat(errors).isEmpty();
    }
}
