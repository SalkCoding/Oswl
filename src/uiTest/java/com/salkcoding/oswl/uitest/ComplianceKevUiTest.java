package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.reporting.ComplianceReportService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ComplianceKevUiTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final LibraryRepository libraries;
    private final ScanComponentRepository components;
    private final CveRepository cves;
    private final ComplianceReportService reports;

    @Test void reportSeparatesUnknownFromUnlistedAcrossLanguages() throws Exception {
        var project = projects.save(Project.builder().name("KEV coverage fixture").build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build());
        var library = libraries.save(Library.builder().name("kev-report-fixture").version("1.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.UNKNOWN).build());
        components.save(ScanComponent.builder().scanResult(scan).library(library).build());
        cves.save(Cve.builder().library(library).cveId("CVE-2026-0001").kevListed(null).build());
        cves.save(Cve.builder().library(library).cveId("CVE-2026-0002").kevListed(false).build());
        assertThat(reports.build(project.getId()).kevUnknown()).isEqualTo(1);
        assertThat(reports.build(project.getId()).kevTotal()).isZero();
        loginAsTestAdmin();
        Path directory = Path.of("build/reports/roadmap-kev-report");
        Files.createDirectories(directory);
        var errors = new java.util.ArrayList<String>();
        page.onPageError(errors::add);
        for (String lang : List.of("en", "ko", "ja")) {
            var response = page.navigate(url("/projects/" + project.getId() + "/security-center/compliance-report?lang=" + lang));
            assertThat(response.status()).isEqualTo(200);
            String text = page.locator("body").innerText();
            assertThat(text).contains(switch (lang) {
                case "ko" -> "취약점 기록 1건의 KEV 상태가 미확인입니다.";
                case "ja" -> "脆弱性レコード1件のKEV状態が未確認です。";
                default -> "Vulnerability records with unknown KEV status: 1.";
            });
            assertThat(text).doesNotContain("???", "No actively-exploited (CISA KEV) vulnerabilities in the current inventory.");
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                    .setPath(directory.resolve("kev-report-" + lang + ".png")).setFullPage(true));
        }
        assertThat(errors).isEmpty();
    }
}
