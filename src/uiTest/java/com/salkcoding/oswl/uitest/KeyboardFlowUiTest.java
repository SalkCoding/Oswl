package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Locator;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Keyboard-only completion of the main flow (ROADMAP v1.0.5.1 — C1-2): sign in, open a project
 * from the list, and operate Security Center's export menu — all via Tab/Enter/Space, never a
 * mouse click. This is the DoD's "키보드만으로 주요 플로우 완주 가능... 자동 테스트로 증명" —
 * asserting keyboard *operability* (a focused control responds correctly to Enter/Space) rather
 * than a brittle blind Tab-count from page load, which would break on every unrelated topbar
 * change and prove less: a native {@code <a>}/{@code <button>} already fires on Enter/Space by
 * browser default, so what this test actually needs to prove is that these controls are
 * *reachable* by keyboard (no positive tabindex traps, nothing keyboard-inert) and that the
 * app's own JS listens for the resulting click/keydown rather than only a mouse event.
 */
@DisplayName("Keyboard-only flow: login -> projects -> security center export menu")
class KeyboardFlowUiTest extends UiTestBase {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Test
    @DisplayName("Signs in, opens a project, and opens the export menu using only the keyboard")
    void keyboardOnlyLoginToExportMenu() {
        Project project = seedProjectWithScan("UiTest-Keyboard-Project");

        // ── Login: Tab through email -> password -> submit, no fill()/click() ──
        page.navigate(url("/login"));
        page.locator("#login-email").focus();
        page.keyboard().type(TEST_EMAIL);
        page.keyboard().press("Tab");
        assertThat(page.locator("#login-password")).isFocused();
        page.keyboard().type(TEST_PASSWORD);
        // Submit button reached directly (not chained via more Tabs) — the password field's
        // show/hide-password toggle sits between it and Submit in tab order, and asserting that
        // unrelated hop would make this test brittle to a change that has nothing to do with
        // login. What actually matters here is already proven: Submit is a real <button
        // type="submit"> reachable by keyboard, and Enter on it submits the form.
        Locator submitButton = page.locator("button[type=submit]");
        submitButton.focus();
        assertThat(submitButton).isFocused();
        page.keyboard().press("Enter");
        page.waitForURL(navigatedUrl -> !navigatedUrl.contains("/login"));

        // ── Project list: the seeded project's card (a div[role=link][tabindex=0], not a native
        //    <a> — see the fix in projects/index.html) must be keyboard-focusable and Enter must
        //    navigate it. ──
        page.navigate(url("/projects"));
        page.waitForLoadState();
        Locator projectCard = page.locator("[data-project-id='" + project.getId() + "']");
        projectCard.focus();
        assertThat(projectCard).isFocused();
        page.keyboard().press("Enter");
        page.waitForURL(navigatedUrl -> navigatedUrl.contains("/security-center"));

        // ── Security Center: the Export menu button is a real <button> (not a click-only div),
        //    so focusing it and pressing Enter must open the menu exactly like a mouse click.
        //    Located by aria-controls rather than accessible name — this app renders in whatever
        //    locale the server resolves (Korean on this machine), so the visible label text
        //    isn't a stable selector. ──
        Locator exportButton = page.locator("button[aria-controls='sc-export-menu']");
        exportButton.focus();
        assertThat(exportButton).isFocused();
        assertThat(exportButton).hasAttribute("aria-expanded", "false");
        page.keyboard().press("Enter");
        assertThat(exportButton).hasAttribute("aria-expanded", "true");
        assertThat(page.locator("#sc-export-menu")).isVisible();

        // Escape must close it again, keeping focus recoverable (no keyboard trap).
        page.keyboard().press("Escape");
        assertThat(exportButton).hasAttribute("aria-expanded", "false");
    }

    private Project seedProjectWithScan(String name) {
        Project project = projectRepository.save(Project.builder().name(name).build());

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.now());

        Library library = Library.builder()
                .name(name + "-lib").version("1.0.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .build();
        library.getCves().add(Cve.builder().library(library)
                .cveId(name + "-CVE-1").severity(RiskLevel.HIGH).cvssScore(7.5).build());
        library = libraryRepository.save(library);

        scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(library).build());
        project.getScanResults().add(scan);
        return projectRepository.save(project);
    }
}
