package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:risk_order_ui;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RiskOrderUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final ScanComponentRepository components;
    private final LibraryRepository libraries;

    @Test void displayedRiskOrderKeepsCandidatesSeparate() throws Exception {
        var project = projects.save(Project.builder().name("Risk order UI").build());
        var scan = scans.saveAndFlush(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        for (String name : List.of("a-candidate", "b-unscored", "z-high")) {
            var lib = Library.builder().name(name).version("1").ecosystem("NPM").build();
            lib.getCves().add(Cve.builder().library(lib).cveId("CVE-2026-123454")
                    .sources(Set.of(name.equals("a-candidate") ? CveSource.NVD : CveSource.OSV))
                    .severity(name.equals("a-candidate") ? RiskLevel.CRITICAL : name.equals("z-high") ? RiskLevel.HIGH : null).build());
            libraries.saveAndFlush(lib);
            components.saveAndFlush(ScanComponent.builder().scanResult(scan).library(lib).build());
        }
        loginAsTestAdmin();
        Path output = Path.of("build/reports/risk-order-ui"); Files.createDirectories(output);
        for (String lang : List.of("en", "ko", "ja")) {
            page.navigate(url("/projects/" + project.getId() + "/security-center?lang=" + lang));
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
            var rows = page.locator(".component-row");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(rows).hasCount(3);
            assertThat(rows.evaluateAll("rows => rows.map(row => row.dataset.compName)"))
                    .isEqualTo(List.of("z-high 1", "b-unscored 1", "a-candidate 1"));
            rows.last().scrollIntoViewIfNeeded();
            page.locator("#component-rows-container").screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions()
                    .setPath(output.resolve(lang + ".png")));
        }
    }
}
