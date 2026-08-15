package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.options.WaitUntilState;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-scale measurement. First measured the Security Center's initial render at
 * 5,000 components on the {@code content-visibility: auto} fix alone: ~15s, ~125k DOM nodes,
 * ~37MB decoded body — nowhere near the 1s target, which confirmed server-side pagination was
 * actually needed rather than a speculative build. After
 * adding it ({@code SecurityCenterService.populateIndexModel}/{@code queryRows}, the
 * {@code /security-center/rows} endpoint, and the client-side fetch-based filter/sort/load-more
 * in {@code security-center/index.html}), this test re-measures against the same DoD and also
 * exercises pagination and server-side filtering against real seeded data, since a fast page that
 * silently shows the wrong rows would be a worse outcome than the original slow-but-correct one.
 */
@DisplayName("Security Center scale measurement (5,000 components)")
class ScaleMeasurementUiTest extends UiTestBase {

    private static final int COMPONENT_COUNT = 5000;
    private static final int RESTRICTED_LICENSE_COUNT = 715; // i % 7 == 0 for i in [0, 5000)
    private static final long RENDER_BUDGET_MS = 1000;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Test
    @DisplayName("Security Center initial render at 5,000 components — measured against the 1s DoD")
    void securityCenterRendersLargeProjectWithinBudget() throws IOException {
        Project project = seedLargeProject();
        Long projectId = project.getId();

        loginAsTestAdmin();

        // Warm-up request: this is the very first HTTP hit against a freshly-booted Spring
        // context in this test, so an unwarmed run pays one-time JIT/class-loading and (for the
        // new paginated query) H2 query-plan-compilation cost that has nothing to do with the
        // page's actual steady-state render cost — exactly what the 1s DoD is meant to budget.
        // Discard this navigation's timing and measure the second one instead.
        page.navigate(url("/projects/" + projectId + "/security-center"), new com.microsoft.playwright.Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.LOAD));

        long start = System.currentTimeMillis();
        page.navigate(url("/projects/" + projectId + "/security-center"), new com.microsoft.playwright.Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.LOAD));
        long wallClockMs = System.currentTimeMillis() - start;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> nav = (java.util.Map<String, Object>) page.evaluate(
                "() => { const e = performance.getEntriesByType('navigation')[0]; " +
                        "return { domContentLoaded: e.domContentLoadedEventEnd, loadEvent: e.loadEventEnd, " +
                        "transferSize: e.transferSize, encodedBodySize: e.encodedBodySize, " +
                        "decodedBodySize: e.decodedBodySize }; }");
        long domNodeCount = ((Number) page.evaluate("() => document.querySelectorAll('*').length")).longValue();
        long renderedRowCount = page.locator(".component-row").count();

        String report = "components seeded: " + COMPONENT_COUNT + "\n"
                + "wall-clock navigate() time: " + wallClockMs + " ms\n"
                + "Navigation Timing domContentLoadedEventEnd: " + nav.get("domContentLoaded") + " ms\n"
                + "Navigation Timing loadEventEnd: " + nav.get("loadEvent") + " ms\n"
                + "transferSize: " + nav.get("transferSize") + " bytes\n"
                + "encodedBodySize: " + nav.get("encodedBodySize") + " bytes\n"
                + "decodedBodySize: " + nav.get("decodedBodySize") + " bytes\n"
                + "DOM node count: " + domNodeCount + "\n"
                + "rendered .component-row count (one page): " + renderedRowCount + "\n"
                + "1s budget met (wall-clock): " + (wallClockMs <= RENDER_BUDGET_MS) + "\n";

        Path dir = Path.of("build", "reports", "scale");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("security-center-5000.txt"), report);

        // Real pass/fail gate now that pagination is in place: only one page's worth of rows
        // should ever hit the DOM, and that keeps render time under the DoD budget.
        assertThat(wallClockMs).isLessThanOrEqualTo(RENDER_BUDGET_MS);
        assertThat(renderedRowCount).isBetween(1L, 150L);

        // "Showing X of N" must reflect the true total, not just what's on screen — a page that
        // quietly capped the count at the page size would look identical unless checked.
        String body = page.locator("body").innerText();
        assertThat(body).contains(String.valueOf(COMPONENT_COUNT)).withFailMessage(
                "expected the pagination footer to show the true total of %d components; body=%s",
                COMPONENT_COUNT, body);

        // Server-side filtering's own risk is reimplementing rowVisible() in SQL and
        // getting a filter wrong — checking the "Restricted" license filter should re-fetch and
        // show only the 715 seeded restricted-license rows, not silently keep showing the
        // unfiltered page. Selected by its x-model attribute rather than label text since the
        // default test locale renders the label translated (e.g. Korean "제한됨").
        // The checkbox itself is visually hidden (sr-only, styled via a sibling div) — click its
        // wrapping label, same as a real user would click the visible custom checkbox UI.
        page.locator("label:has(input[x-model='filters.licRestricted'])").click();
        page.waitForTimeout(500); // let the async refetch triggered by the filters $watch settle
        String filteredBody = page.locator("body").innerText();
        assertThat(filteredBody).contains(String.valueOf(RESTRICTED_LICENSE_COUNT));
    }

    private Project seedLargeProject() {
        Project project = projectRepository.save(Project.builder().name("UiTest-Scale-Project").build());

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.now());

        List<Library> libraries = new ArrayList<>(COMPONENT_COUNT);
        for (int i = 0; i < COMPONENT_COUNT; i++) {
            libraries.add(Library.builder()
                    .name("scale-lib-" + i).version("1.0." + (i % 50)).ecosystem("MAVEN")
                    .licenseStatus(i % 7 == 0 ? LicenseStatus.RESTRICTED : LicenseStatus.PERMITTED)
                    .build());
        }
        libraries = libraryRepository.saveAll(libraries);

        for (Library lib : libraries) {
            scan.getComponents().add(ScanComponent.builder().scanResult(scan).library(lib).build());
        }
        project.getScanResults().add(scan);
        return projectRepository.save(project);
    }
}
