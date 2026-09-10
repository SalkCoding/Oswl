package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Media;
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
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestConstructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SecurityPrintEvidenceUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final LibraryRepository libraries;
    private final ScanComponentRepository components;
    private final MessageSource messages;

    @ParameterizedTest
    @ValueSource(strings = {"en", "ko", "ja"})
    void printKeepsUnknownVersionStatusAndLongEvidence(String lang) throws Exception {
        String evidence = ("NuGet lock source=\"" + "LongPath".repeat(30)
                + "/packages.lock.json\" target=\"net8.0/win-x64\"\n").repeat(3).strip();
        var project = projects.save(Project.builder().name("Print evidence " + lang).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        for (String state : new String[]{"unknown", "latest", "outdated", "deprecated"}) {
            var library = Library.builder().name("print-" + lang + "-" + state).version("1.0.0").ecosystem("NUGET")
                    .licenseStatus(LicenseStatus.UNKNOWN).build();
            if (!state.equals("unknown")) library.updateVersionStatus(state.equals("latest"),
                    state.equals("deprecated") ? "fixture" : null, state.equals("outdated") ? "1.1.0" : null);
            library = libraries.save(library);
            components.save(ScanComponent.builder().scanResult(scan).library(library)
                    .dependencyInfo(state.equals("unknown") ? evidence : "Direct").build());
        }
        for (var status : new LicenseStatus[]{LicenseStatus.PERMITTED, LicenseStatus.CAUTION, LicenseStatus.RESTRICTED}) {
            var library = libraries.save(Library.builder().name("license-" + lang + "-" + status).version("1.0.0")
                    .ecosystem("NUGET").licenseStatus(status).build());
            components.save(ScanComponent.builder().scanResult(scan).library(library).dependencyInfo("Direct").build());
        }
        loginAsTestAdmin();
        page.setViewportSize(800, 1000);
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
        page.navigate(url("/projects/" + project.getId() + "/security-center/print?lang=" + lang));
        Path output = Path.of("build/reports/security-print-evidence-ui");
        Files.createDirectories(output);
        page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(lang + ".png")).setFullPage(true));
        var softly = new SoftAssertions();
        softly.assertThat((Boolean) page.locator(".comp-table").evaluate(
                "e => e.getBoundingClientRect().right <= e.closest('.page').getBoundingClientRect().right + 1"))
                .as("table stays within print page").isTrue();
        Locale locale = Locale.forLanguageTag(lang);
        var licenseLegend = page.locator(".risk-block").nth(1);
        softly.assertThat(licenseLegend.locator(".risk-legend-item").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText(messages.getMessage("securityCenter.filter.unknown", null, locale)))
                .locator(".risk-count").innerText()).as("unknown licenses").isEqualTo("4");
        softly.assertThat(licenseLegend.locator(".risk-legend-item").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText(messages.getMessage("securityCenter.filter.permitted", null, locale)))
                .locator(".risk-count").innerText()).as("permitted licenses").isEqualTo("1");
        for (String category : new String[]{"caution", "restricted"}) softly.assertThat(licenseLegend.locator(".risk-legend-item").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText(messages.getMessage("securityCenter.filter." + category, null, locale)))
                .locator(".risk-count").innerText()).as(category).isEqualTo("1");
        softly.assertThat(licenseLegend.locator(".risk-bar-seg.unknown").getAttribute("style")).isEqualTo("flex:4");
        softly.assertThat(licenseLegend.locator(".risk-bar-seg.permitted").getAttribute("style")).isEqualTo("flex:1");
        for (String state : new String[]{"unknown", "latest", "outdated", "deprecated"}) {
            var row = page.locator(".comp-table tbody tr").filter(
                    new com.microsoft.playwright.Locator.FilterOptions().setHasText("print-" + lang + "-" + state));
            assertThat(row.count()).isEqualTo(1);
            String key = switch (state) {
                case "latest" -> "securityCenter.filter.upToDate";
                case "outdated" -> "securityCenter.filter.outdated";
                case "deprecated" -> "securityCenter.filter.deprecated";
                default -> "common.unknown";
            };
            softly.assertThat(row.locator(".patch-text").innerText()).as(state).isEqualTo(messages.getMessage(key, null, locale));
            softly.assertThat(row.innerText()).contains(messages.getMessage("securityCenter.table.notAnalyzed", null, locale));
            if (state.equals("unknown")) softly.assertThat(row.locator(".comp-dep").textContent()).startsWith(evidence);
        }
        softly.assertAll();
    }
}
