package com.salkcoding.oswl.uitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
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
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.scan.ScanAssessmentService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestConstructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ScanArchiveEvidenceHttpTest extends UiTestBase {
    private final ProjectRepository projects;
    private final ScanResultRepository scans;
    private final ScanComponentRepository components;
    private final LibraryRepository libraries;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    @ParameterizedTest
    @ValueSource(strings = {"preserved", "legacy", "mismatch", "ambiguous", "coerced"})
    void realHttpPreservesEvidenceAndRequiresSystemAdmin(String state) throws Exception {
        String suffix = UUID.randomUUID().toString();
        var project = projects.save(Project.builder().name("archive-http-" + suffix).build());
        var library = Library.builder().name("evidence-" + suffix).version("1").ecosystem("NPM").build();
        library.recordLookupOutcomes(Map.of("OSV", "UNAVAILABLE"));
        library.getCves().add(Cve.builder().library(library).cveId("CVE-2026-123450")
                .sources(Set.of(CveSource.OSV)).fixVersionConflictCandidates(Set.of("2", "3")).build());
        library = libraries.saveAndFlush(library);
        String evidence = state.equals("legacy") ? null : new ObjectMapper().writeValueAsString(
                new ScanAssessment(1, "2026-09-01T00:00:00Z", List.of(ScanAssessmentService.fromLibrary(library))));
        if (state.equals("ambiguous")) evidence = evidence.replace("\"OSV\":\"UNAVAILABLE\"",
                "\"OSV\":\"UNAVAILABLE\",\"OSV\":\"RESOLVED\"");
        if (state.equals("coerced")) evidence = evidence.replace("\"formatVersion\":1", "\"formatVersion\":1.9");
        var old = scans.saveAndFlush(ScanResult.builder().project(project).version("1.0")
                .status(ScanStatus.COMPLETED).assessmentJson(evidence).build());
        if (!state.equals("mismatch")) components.saveAndFlush(ScanComponent.builder().scanResult(old).library(library).build());
        scans.saveAndFlush(ScanResult.builder().project(project).version("2.0").status(ScanStatus.COMPLETED).build());
        String endpoint = url("/api/admin/projects/" + project.getId() + "/archive-scans/export?retainCount=1");
        String email = "archive-" + suffix + "@example.test";
        users.save(User.builder().email(email).displayName("Archive reader")
                .passwordHash(encoder.encode(TEST_PASSWORD)).enabled(true).isSystemAdmin(false).build());
        page.navigate(url("/login"));
        page.fill("#login-email", email);
        page.fill("#login-password", TEST_PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(value -> !value.contains("/login"));
        var denied = context.request().get(endpoint);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.text()).doesNotContain("CVE-2026-123450", "assessmentJson");
        denied.dispose();
        context.clearCookies();
        loginAsTestAdmin();
        var response = context.request().get(endpoint);
        if (state.equals("mismatch") || state.equals("ambiguous") || state.equals("coerced")) {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.text()).doesNotContain("CVE-2026-123450");
        } else {
            assertThat(response.status()).isEqualTo(200);
            var body = new ObjectMapper().readTree(response.text());
            assertThat(body.size()).isEqualTo(1);
            assertThat(body.get(0).path("scanId").asLong()).isEqualTo(old.getId());
            assertThat(body.get(0).has("assessmentJson")).isTrue();
            if (evidence == null) assertThat(body.get(0).get("assessmentJson").isNull()).isTrue();
            else {
                assertThat(body.get(0).get("assessmentJson").asText()).isEqualTo(evidence);
                var restored = ScanAssessmentService.read(body.get(0).get("assessmentJson").asText());
                assertThat(restored.libraries()).singleElement().satisfies(saved -> {
                    assertThat(saved.lookupComplete()).isFalse();
                    assertThat(saved.lookupOutcomes()).containsEntry("OSV", "UNAVAILABLE");
                    assertThat(saved.findings()).singleElement().satisfies(finding -> {
                        assertThat(finding.fixVersion()).isNull();
                        assertThat(finding.fixVersionConflictCandidates()).containsExactlyInAnyOrder("2", "3");
                        assertThat(finding.sources()).containsExactly(CveSource.OSV);
                    });
                });
            }
        }
        response.dispose();
        var unchanged = scans.findById(old.getId()).orElseThrow();
        assertThat(unchanged.isArchived()).isFalse();
        assertThat(unchanged.getAssessmentJson()).isEqualTo(evidence);
        assertThat(components.countByScanResultId(old.getId())).isEqualTo(state.equals("mismatch") ? 0 : 1);
    }
}
