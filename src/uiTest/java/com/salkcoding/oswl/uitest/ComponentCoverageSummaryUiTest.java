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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestConstructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ComponentCoverageSummaryUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final LibraryRepository libraries;
    private final ScanComponentRepository components;
    private final MessageSource messages;

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void storedScanEvidenceIsDistinguishedFromCurrentLookupCache(boolean preserved) throws Exception {
        var project = projects.save(Project.builder().name("Evidence origin fixture "+preserved).githubRepo("fixture/repository-"+preserved).build());
        var library = libraries.save(Library.builder().name("origin-"+preserved).version("1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.UNKNOWN).build());
        String assessment = preserved ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new com.salkcoding.oswl.dto.scan.ScanAssessment(1,LocalDateTime.now().toString(),java.util.List.of(
                        com.salkcoding.oswl.service.scan.ScanAssessmentService.fromLibrary(library)))) : null;
        var scan = scans.save(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED)
                .assessmentJson(assessment).build());
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        loginAsTestAdmin();
        Path output = Path.of("build/reports/scan-evidence-ui"); Files.createDirectories(output);
        for (String lang : new String[]{"ko","en","ja"}) {
            Locale locale = Locale.forLanguageTag(lang);
            String expected = messages.getMessage("scanEvidence."+(preserved ? "preserved" : "unverified"),null,locale);
            for (String route : new String[]{"security-center", "components/"+component.getId()}) {
                page.navigate(url("/projects/"+project.getId()+"/"+route+"?lang="+lang));
                var note = page.locator("[data-scan-evidence]");
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(note).containsText(expected);
                assertThat(note.innerText()).contains("fixture/repository",String.valueOf(scan.getId()));
                assertThat(note.locator("a").getAttribute("href")).isEqualTo("/projects/"+project.getId()+"/scan-history");
                if (route.startsWith("components/")) assertThat(page.locator("#component-detail-content").innerText())
                        .contains(messages.getMessage("securityCenter.table.notAnalyzed",null,locale));
                page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(preserved+"-"+lang+"-"+route.split("/")[0]+".png")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE","ERROR","title"})
    void sourceAndLookupEvidenceHaveSectionSpacingAndReadableDates(String lookupState) throws Exception {
        var project = projects.save(Project.builder().name("Detail evidence layout "+lookupState).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        var library = Library.builder().name("layout-fixture-"+lookupState).version("1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.UNKNOWN).build();
        library.recordLookupOutcomes(Map.of("OSV","RESOLVED","NVD","UNSUPPORTED","GITHUB_ADVISORY","NOT_CONFIGURED","DEPS_DEV",lookupState));
        library.markFetched();
        library = libraries.save(library);
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library)
                .reachabilityAnalysis(new com.salkcoding.oswl.dto.scan.SourceAnalysisDetails(
                        "JAVASCRIPT",0,0,"UNKNOWN",false,"NO_LANGUAGE_FILES",java.util.List.of()).toJson()).build());
        loginAsTestAdmin();
        Path output = Path.of("build/reports/component-detail-layout-ui/"+lookupState);
        Files.createDirectories(output);
        for (String lang : new String[]{"ko","en","ja"}) {
            page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=" + lang));
            var detail = page.locator("#component-detail-content");
            var text = detail.getByText(messages.getMessage("componentDetail.sourceAnalysis",null,Locale.forLanguageTag(lang)),
                    new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true));
            assertThat((Boolean) text.evaluate("e => {const s=e.closest('section'); return !!s && parseFloat(getComputedStyle(s).paddingLeft)>=24;}"))
                    .as("source evidence belongs to a padded section").isTrue();
            assertThat(detail.innerText()).doesNotContain("??componentDetail", library.getVulnerabilityLookupAt().toString());
            assertThat(detail.locator("time[datetime]").count()).isPositive();
            assertThat(detail.locator("[data-lookup-status]").innerText()).contains("DEPS_DEV",
                    !lookupState.equals("UNAVAILABLE") ? lookupState : messages.getMessage("componentDetail.lookup.UNAVAILABLE",null,Locale.forLanguageTag(lang)));
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(lang+".png")).setFullPage(true));
            page.navigate(url("/projects/" + project.getId() + "/security-center?lang=" + lang));
            page.locator(".component-row").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("layout-fixture")).click();
            var panel = page.locator("#slideout-content #component-detail-content");
            panel.locator("[data-source-analysis]").waitFor();
            panel.locator("[data-lookup-status]").scrollIntoViewIfNeeded();
            assertThat((Boolean) panel.locator("[data-lookup-status]").evaluate("e => e.scrollWidth <= e.clientWidth + 1")).isTrue();
            assertThat(panel.innerText()).doesNotContain("??componentDetail");
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(lang+"-drawer.png")));

        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "partial", "complete", "legacy"})
    void summariesRequireCompletedCoverageInEveryLanguage(String coverage) throws Exception {
        var project = projects.save(Project.builder().name("Coverage summary " + coverage).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = Library.builder().name("summary-" + coverage).version("1.0.0").ecosystem("NUGET")
                .licenseStatus(LicenseStatus.UNKNOWN).fetchedAt(coverage.equals("missing") ? null : LocalDateTime.now())
                .vulnerabilityLookupOutcomes(coverage.equals("legacy") ? null : coverage.equals("partial") ? Map.of("OSV", "RESOLVED", "GITHUB_ADVISORY", "UNAVAILABLE")
                        : Map.of("OSV", "RESOLVED")).build();
        if (!coverage.equals("missing") && !coverage.equals("legacy")) library.recordLookupOutcomes(library.getVulnerabilityLookupOutcomes());
        library.updateVersionStatus(true, null, null);
        library = libraries.save(library);
        var component = components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        loginAsTestAdmin();
        Path output = Path.of("build/reports/component-coverage-summary-ui");
        Files.createDirectories(output);
        for (String lang : new String[]{"en", "ko", "ja"}) {
            page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=" + lang));
            String body = page.locator("#component-detail-content").innerText();
            Locale locale = Locale.forLanguageTag(lang);
            String none = messages.getMessage("componentDetail.security.none", null, locale);
            String description = messages.getMessage("componentDetail.desc.noVulns", null, locale);
            String safe = messages.getMessage("componentDetail.safe.hint", null, locale);
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(coverage + "-" + lang + ".png")).setFullPage(true));
            if (coverage.equals("complete")) assertThat(body).contains(none, description, safe);
            else assertThat(body).doesNotContain(none, description, safe)
                    .contains(messages.getMessage("securityCenter.table.notAnalyzed", null, locale));
            assertThat(body).doesNotContain("This component is safe to use", "안전하게 사용할 수 있습니다", "安全に使用できます");
        }
    }
}
