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
    @ValueSource(strings = {"missing", "partial", "complete"})
    void summariesRequireCompletedCoverageInEveryLanguage(String coverage) throws Exception {
        var project = projects.save(Project.builder().name("Coverage summary " + coverage).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = Library.builder().name("summary-" + coverage).version("1.0.0").ecosystem("NUGET")
                .licenseStatus(LicenseStatus.UNKNOWN).fetchedAt(coverage.equals("missing") ? null : LocalDateTime.now())
                .vulnerabilityLookupOutcomes(coverage.equals("partial") ? Map.of("OSV", "RESOLVED", "GITHUB_ADVISORY", "UNAVAILABLE")
                        : Map.of("OSV", "RESOLVED")).build();
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
