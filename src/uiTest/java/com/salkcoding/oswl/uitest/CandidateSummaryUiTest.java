package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Page;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.test.context.TestConstructor;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:candidate_summary_ui;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CandidateSummaryUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final ScanComponentRepository components;
    private final LibraryRepository libraries;
    private final MessageSource messages;

    @Test void candidatesAndLegacyUnknownRemainVisibleAcrossSummaryPages() throws Exception {
        var project = projects.save(Project.builder().name("Candidate summary UI").build());
        var library = Library.builder().name("candidate-summary").version("1").ecosystem("CONAN").build();
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123450")
                .sources(Set.of(CveSource.NVD)).severity(RiskLevel.CRITICAL).build());
        library = libraries.saveAndFlush(library);
        var scan = scans.saveAndFlush(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        components.saveAndFlush(ScanComponent.builder().scanResult(scan).library(library).build());
        loginAsTestAdmin();
        Path output = Path.of("build/reports/candidate-summary-ui"); Files.createDirectories(output);
        for (boolean legacy : new boolean[]{false,true}) {
            if (legacy) {
                scan.archive(1, new int[]{1,0,0,0,0}, new int[]{0,0,1,0});
                scans.saveAndFlush(scan);
            }
            for (String lang : new String[]{"en","ko","ja"}) {
                String expected = messages.getMessage(legacy ? "scanSummary.matchReviewUnknown" : "scanSummary.matchReview",
                        legacy ? null : new Object[]{1}, Locale.forLanguageTag(lang));
                for (String route : new String[]{"/projects", "/projects/"+project.getId()+"/security-center",
                        "/projects/"+project.getId()+"/risk-trend", "/projects/"+project.getId()+"/security-center/print", "/org-dashboard", "/org-dashboard/summary"}) {
                    page.navigate(url(route+"?lang="+lang));
                    var notices = page.locator("[data-match-review-summary]");
                    com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(notices.first()).isVisible();
                    assertThat(notices.allTextContents()).anySatisfy(text -> assertThat(text).contains(expected));
                    assertThat(page.locator("body").innerText()).doesNotContain("??scanSummary");
                    page.evaluate("window.scrollTo(0,0)");
                    page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(legacy+"-"+lang+"-"+route.replace('/','_')+".png")).setFullPage(true));
                }
            }
        }
    }
}
