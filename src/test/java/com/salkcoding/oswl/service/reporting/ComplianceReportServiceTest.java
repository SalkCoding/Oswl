package com.salkcoding.oswl.service.reporting;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.ScanAssessment;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.scan.ScanAssessmentService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComplianceReportServiceTest {
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void candidatesCannotBecomeConfirmedKevOrFixAdvice(boolean preserved, boolean packageEvidence) throws Exception {
        var projects = mock(ProjectRepository.class);
        var scans = mock(ScanResultRepository.class);
        var components = mock(ScanComponentRepository.class);
        var libraries = mock(LibraryRepository.class);
        var service = new ComplianceReportService(projects, scans, components, libraries);
        var project = Project.builder().id(1L).name("Candidate review").build();
        var scan = ScanResult.builder().id(2L).project(project).status(ScanStatus.COMPLETED).build();
        var library = Library.builder().id(3L).name("same-name").version("1.0").build();
        library.getCves().add(Cve.builder().cveId("CVE-2026-123450").severity(packageEvidence ? RiskLevel.CRITICAL : null)
                .sources(Set.of(CveSource.NVD)).matchConfidence(MatchConfidence.HIGH)
                .kevListed(true).fixVersion("99.0-candidate").build());
        if (packageEvidence) library.getCves().add(Cve.builder().cveId("CVE-2026-123451").severity(RiskLevel.HIGH)
                .sources(Set.of(CveSource.OSV, CveSource.NVD)).matchConfidence(MatchConfidence.LOW)
                .kevListed(true).fixVersion("2.0").build());
        if (preserved) {
            org.springframework.test.util.ReflectionTestUtils.setField(scan,"assessmentJson",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    new ScanAssessment(1,"2026-09-12T00:00:00Z",List.of(ScanAssessmentService.fromLibrary(library)))));
        } else when(libraries.findByScanResultIdWithCves(2L)).thenReturn(List.of(library));
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(scans.findRecentCompleted(1L,1)).thenReturn(List.of(scan));
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var report = service.build(1L);
        assertThat(report.matchReviewRows()).singleElement().satisfies(row -> {
            assertThat(row.vulnerabilityId()).isEqualTo("CVE-2026-123450");
            assertThat(row.confidence()).isEqualTo("HIGH");
            assertThat(row.componentName()).isEqualTo("same-name");
        });
        assertThat(report.criticalCves()).isZero();
        assertThat(report.highCves()).isEqualTo(packageEvidence ? 1 : 0);
        assertThat(report.kevTotal()).isEqualTo(packageEvidence ? 1 : 0);
        assertThat(report.untriagedRiskComponents()).isEqualTo(1);
        render(report, preserved, packageEvidence);
        if (!packageEvidence) {
            assertThat(report.kevRows()).isEmpty();
            return;
        }
        assertThat(report.kevRows()).singleElement().satisfies(row -> {
            assertThat(row.cveId()).isEqualTo("CVE-2026-123451");
            assertThat(row.fixVersion()).isEqualTo("2.0");
        });
    }

    private void render(com.salkcoding.oswl.dto.ComplianceReportDto report, boolean preserved, boolean packageEvidence) throws Exception {
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        var resolver = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        engine.setTemplateResolver(resolver);
        var messages = new org.springframework.context.support.ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        engine.setTemplateEngineMessageSource(messages);
        engine.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override protected String computeContextPath(org.thymeleaf.context.IExpressionContext context,
                    String base, Map<String,Object> parameters) { return ""; }
        });
        for (String language : List.of("en", "ko", "ja")) {
            var context = new org.thymeleaf.context.Context(Locale.forLanguageTag(language));
            context.setVariable("report",report);
            context.setVariable("branding",new com.salkcoding.oswl.dto.ReportBrandingResponse(null,null,null,false));
            String html = engine.process("reports/compliance-report",context);
            assertThat(html).contains("CVE-2026-123450", "same-name")
                    .doesNotContain("99.0-candidate", "??report.compliance");
            String heading = switch (language) {
                case "ko" -> "매칭 검토가 필요한 후보";
                case "ja" -> "照合の確認が必要な候補";
                default -> "Matching candidates requiring review";
            };
            assertThat(html).contains(heading);
            var directory = java.nio.file.Path.of("build/cpe-report-preview");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve(language + "-" + preserved + "-" + packageEvidence + ".html"),html);
        }
    }
}
