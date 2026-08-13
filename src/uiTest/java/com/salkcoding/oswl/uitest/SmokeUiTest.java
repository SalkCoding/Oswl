package com.salkcoding.oswl.uitest;

import com.deque.html.axecore.results.AxeResults;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real app and drives it end to end through headless Chromium: login, project list,
 * Security Center. This is the case the roadmap's V1 exists for — curl/grep against rendered
 * HTML cannot tell whether the page a user actually sees works, only whether the markup exists.
 *
 * <p>Does not assert on axe violations — that assertion belongs to C1-2, once the color-contrast
 * and keyboard-navigation fixes (C1-1/C1-2) are in. Here we only prove the harness itself works
 * end to end and leave a report behind.
 */
@DisplayName("UI smoke: login -> project list -> Security Center")
class SmokeUiTest extends UiTestBase {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @DisplayName("Logs in, opens the project list, and reaches Security Center for a seeded project")
    void loginProjectsSecurityCenter() throws IOException {
        Project project = projectRepository.save(Project.builder().name("UiTest-Smoke-Project").build());

        loginAsTestAdmin();

        page.navigate(url("/projects"));
        page.waitForLoadState();
        assertThat(page.url()).contains("/projects");

        writeAxeReport("projects", runAxeScan());

        page.navigate(url("/projects/" + project.getId() + "/security-center"));
        page.waitForLoadState();
        assertThat(page.url()).contains("/security-center");

        writeAxeReport("security-center", runAxeScan());
    }

    private void writeAxeReport(String name, AxeResults results) throws IOException {
        Path dir = Path.of("build", "reports", "axe");
        Files.createDirectories(dir);
        int violationCount = results.getViolations() != null ? results.getViolations().size() : 0;
        StringBuilder report = new StringBuilder();
        report.append("url: ").append(results.getUrl()).append('\n');
        report.append("violations: ").append(violationCount).append('\n');
        if (results.getViolations() != null) {
            results.getViolations().forEach(v -> {
                report.append(" - [").append(v.getImpact()).append("] ").append(v.getId())
                        .append(" (").append(v.getNodes() != null ? v.getNodes().size() : 0)
                        .append(" nodes): ").append(v.getDescription()).append('\n');
                if (v.getNodes() != null) {
                    v.getNodes().forEach(n -> report.append("     target=")
                            .append(n.getTarget()).append(" html=").append(n.getHtml()).append('\n'));
                }
            });
        }
        Files.writeString(dir.resolve(name + ".txt"), report.toString());
        System.out.println("[uiTest] " + name + ": " + violationCount + " axe violation rule(s) — see build/reports/axe/" + name + ".txt");
    }
}
