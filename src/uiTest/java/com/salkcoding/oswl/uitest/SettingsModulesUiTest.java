package com.salkcoding.oswl.uitest;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SettingsModulesUiTest extends UiTestBase {
    @Test void extractedSettingsInitializeOnceAcrossNavigationAndMobile() throws Exception {
        List<String> errors = new ArrayList<>();
        page.onPageError(errors::add);
        Map<String, Integer> requests = new HashMap<>();
        page.onRequest(r -> {
            if (r.method().equals("GET") && r.url().contains("/api/")) requests.merge(r.url(), 1, Integer::sum);
        });
        loginAsTestAdmin();
        StringBuilder report = new StringBuilder();
        for (String tab : List.of("admin", "ai", "cache", "cli", "diagnostics", "license-policy", "policy", "reports", "vcs", "webhooks", "config-transfer")) {
            requests.clear();
            page.navigate(url("/settings?tab=" + tab + "&lang=en"));
            page.waitForFunction("() => window.Alpine && document.readyState === 'complete'");
            page.waitForTimeout(300);
            assertThat(errors).as("settings tab %s", tab).isEmpty();
            if (tab.equals("admin")) assertThat(requests.getOrDefault(url("/api/admin/users"), 0)).isEqualTo(1);
            if (tab.equals("ai")) assertThat(requests.getOrDefault(url("/api/settings/ai"), 0)).isEqualTo(1);
            var cdp = page.context().newCDPSession(page);
            try {
                report.append(tab).append(" requests=").append(requests)
                        .append(" domNodes=").append(page.evaluate("() => document.querySelectorAll('*').length"))
                        .append(" heap=").append(cdp.send("Runtime.getHeapUsage")).append("\n");
            } finally {
                cdp.detach();
            }
        }
        page.setViewportSize(390, 844);
        for (String tab : List.of("admin", "ai")) {
            page.navigate(url("/settings?tab=" + tab + "&lang=ja"));
            page.keyboard().press("Tab");
            assertThat(page.evaluate("() => document.activeElement !== document.body")).isEqualTo(true);
            page.keyboard().press("Escape");
            assertThat(errors).isEmpty();
            var serious = runAxeScan().getViolations().stream()
                    .filter(v -> "serious".equals(v.getImpact()) || "critical".equals(v.getImpact())).toList();
            assertThat(serious).as("mobile settings %s", tab).isEmpty();
        }
        Path output = Path.of("build/reports/performance/settings.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report);
    }
}
