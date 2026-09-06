package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.client.*;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.service.ingest.*;
import com.salkcoding.oswl.service.gate.GatePolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Deterministic transport fixtures: these results do not certify current upstream coverage. */
class DetectionCoverageUiTest extends UiTestBase {
    @Autowired DependencyManifestParserService parser;
    @Autowired ScanIngestService ingest;
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired ScanComponentRepository components;
    @Autowired GatePolicyService gate;
    @MockitoBean OsvClient osv;
    @MockitoBean DepsDevClient deps;
    @MockitoBean GitHubAdvisoryClient github;
    @MockitoBean NvdClient nvd;
    @MockitoBean EpssClient epss;
    @MockitoBean KevCatalogService kev;
    @TempDir Path root;

    @Test void parsedFixturesReachEnrichmentBrowserAndGate() throws Exception {
        when(deps.getVersionsBatch(anyList())).thenReturn(List.of());
        when(github.findByComponentKeys(any())).thenReturn(Map.of());
        when(nvd.findByComponentKeys(any())).thenReturn(Map.of());
        when(epss.fetchScores(anyList())).thenReturn(Map.of());
        when(osv.queryBatch(anyList())).thenAnswer(call -> {
            List<OsvClient.OsvQuery> queries = call.getArgument(0);
            return queries.stream().map(q -> q.name().equals("coverage-unavailable") ? OsvClient.OsvResult.unresolved() : new OsvClient.OsvResult(q.name().equals("coverage-malicious")
                    ? List.of(new OsvClient.OsvVuln("MAL-FIXTURE-001", null, "Deterministic malicious fixture", null, null))
                    : List.of())).toList();
        });
        loginAsTestAdmin();
        StringBuilder report = new StringBuilder("Source: injected advisory fixtures, no live database claim.\n");
        for (String scenario : List.of("malicious", "clean", "unsupported", "unavailable")) {
            Path dir = Files.createDirectory(root.resolve(scenario));
            if (scenario.equals("unsupported")) {
                Files.writeString(dir.resolve(".gitmodules"), "[submodule \"fixture-native\"]\npath = native\nurl = https://example.invalid/native.git\n");
                git(dir, "init");
                git(dir, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "--allow-empty", "-m", "fixture");
                git(dir, "update-index", "--add", "--cacheinfo", "160000,1111111111111111111111111111111111111111,native");
                git(dir, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-m", "submodule fixture");
            } else Files.writeString(dir.resolve("requirements.txt"), "coverage-" + scenario + "==1.0.0\n");
            var parsed = parser.parseDependencies(dir, "coverage-" + scenario);
            assertThat(parsed.components()).hasSize(1);
            Project project = projects.save(Project.builder().name("Coverage " + scenario).build());
            Long scanId = ingest.ingest(project.getId(), ScanPayload.create("1.0", parsed.components())).getId();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
            ScanStatus status;
            do {
                status = scans.findById(scanId).orElseThrow().getStatus();
                if (status == ScanStatus.COMPLETED || status == ScanStatus.FAILED) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(status).isEqualTo(ScanStatus.COMPLETED);
            var component = components.findByScanResultId(scanId).getFirst();
            var result = gate.evaluate(project.getId(), GatePolicyService.GateOptions.defaults());
            assertThat(result.passed()).isEqualTo(!scenario.equals("malicious"));
            page.navigate(url("/projects/" + project.getId() + "/security-center?lang=en"));
            if (scenario.equals("unsupported") || scenario.equals("unavailable")) {
                assertThat(component.getLibrary().getFetchedAt()).isNull();
                assertThat(page.locator("body").innerText()).contains("Not analyzed");
            }
            page.navigate(url("/projects/" + project.getId() + "/components/" + component.getId() + "?lang=en"));
            String detail = page.locator("body").innerText();
            if (scenario.equals("unsupported") || scenario.equals("unavailable")) assertThat(detail).doesNotContain("No security vulnerabilities detected").contains("Vulnerability lookup has not completed successfully");
            if (scenario.equals("malicious")) assertThat(detail).contains("MAL-FIXTURE-001");
            report.append(scenario).append(" parsed=1 status=").append(status)
                    .append(" fetched=").append(component.getLibrary().getFetchedAt() != null)
                    .append(" gatePassed=").append(result.passed())
                    .append(" detailClaimsNoVulnerabilities=").append(detail.contains("No security vulnerabilities detected"))
                    .append('\n');
        }
        Path out = Path.of("build/reports/stage4/detection-coverage.txt");
        Files.createDirectories(out.getParent()); Files.writeString(out, report);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
