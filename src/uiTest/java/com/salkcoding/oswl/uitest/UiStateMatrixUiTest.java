package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.entity.*;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.*;
import com.salkcoding.oswl.domain.entity.project.*;
import com.salkcoding.oswl.repository.project.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class UiStateMatrixUiTest extends UiTestBase {
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired UserRepository users;
    @Autowired RoleTemplateRepository roles;
    @Autowired PasswordEncoder passwords;

    @Test void emptyProjectPagesAndAllSettingsRenderAcrossLanguages() throws Exception {
        Project project = projects.save(Project.builder().name("Empty-state-" + UUID.randomUUID()).build());
        List<String> errors = new ArrayList<>(); page.onPageError(errors::add);
        loginAsTestAdmin();
        List<String> paths = new ArrayList<>(List.of("/projects", "/org-dashboard", "/onboarding"));
        for (String view : List.of("security-center", "scan-history", "risk-trend", "version-diff", "license"))
            paths.add("/projects/" + project.getId() + "/" + view);
        for (String tab : List.of("admin", "security", "ai", "vcs", "cli", "cache", "license-policy", "policy", "webhooks", "reports", "diagnostics", "config-transfer"))
            paths.add("/settings?tab=" + tab);
        Path report = Path.of("build/reports/stage4/ui-matrix.txt"); Files.createDirectories(report.getParent());
        StringBuilder result = new StringBuilder();
        for (String lang : List.of("en", "ko", "ja")) {
            for (String path : paths) {
                errors.clear();
                var response = page.navigate(url(path + (path.contains("?") ? "&" : "?") + "lang=" + lang));
                page.waitForTimeout(120);
                assertThat(response.status()).as("%s %s", lang, path).isEqualTo(200);
                assertThat(errors).as("browser errors: %s %s", lang, path).isEmpty();
                assertThat(page.locator("body").innerText()).isNotBlank();
                result.append(lang).append(' ').append(path).append(" status=200 pageErrors=0\n");
            }
        }
        Files.writeString(report, result);
    }

    @Test void readOnlyMembershipAndNoPermissionAreVisibleInRealBrowserResponses() throws Exception {
        Project allowed = projects.save(Project.builder().name("Visible membership").build());
        Project denied = projects.save(Project.builder().name("Hidden membership").build());
        RoleTemplate role = roles.save(RoleTemplate.builder().name("UI reader " + UUID.randomUUID())
                .permissions(EnumSet.of(Permission.PROJECT_VIEW, Permission.SECURITY_CENTER_VIEW,
                        Permission.SCAN_HISTORY_VIEW, Permission.RISK_TREND_VIEW, Permission.VERSION_DIFF_VIEW, Permission.LICENSE_VIEW)).build());
        User reader = users.save(User.builder().email("reader-" + UUID.randomUUID() + "@example.test")
                .passwordHash(passwords.encode(TEST_PASSWORD)).displayName("UI reader").enabled(true)
                .roleTemplates(new HashSet<>(Set.of(role))).build());
        members.save(ProjectMember.builder().project(allowed).userId(reader.getId()).build());
        login(reader.getEmail());
        page.navigate(url("/projects?lang=en"));
        assertThat(page.locator("body").innerText()).contains("Visible membership").doesNotContain("Hidden membership");
        for (String view : List.of("security-center", "scan-history", "risk-trend", "version-diff", "license")) {
            assertThat(page.navigate(url("/projects/" + allowed.getId() + "/" + view)).status()).isEqualTo(200);
            assertThat(page.navigate(url("/projects/" + denied.getId() + "/" + view)).status()).as(view).isEqualTo(403);
            assertThat(page.locator("body").innerText()).doesNotContain("Hidden membership");
        }
        page.navigate(url("/settings?tab=admin&lang=en"));
        assertThat(page.locator("[x-data='adminTab()']").count()).isZero();
        assertThat(page.locator("body").innerText()).contains("No accessible settings");
        var response = context.request().get(url("/api/admin/users")); assertThat(response.status()).isEqualTo(403);
        context.clearCookies();
        assertThat(page.navigate(url("/projects"))).isNotNull();
        assertThat(page.url()).contains("/login");
        User none = users.save(User.builder().email("none-" + UUID.randomUUID() + "@example.test")
                .passwordHash(passwords.encode(TEST_PASSWORD)).displayName("No permissions").enabled(true).build());
        login(none.getEmail());
        assertThat(page.navigate(url("/projects" )).status()).isEqualTo(403);
    }

    @Test void settingsMobileKeyboardAndAxeAfterNavigation() throws Exception {
        loginAsTestAdmin(); page.setViewportSize(390, 844);
        List<String> failures = new ArrayList<>();
        Path report=Path.of("build/reports/stage4/mobile-settings.txt"); Files.createDirectories(report.getParent());
        StringBuilder result=new StringBuilder();
        for (String tab : List.of("admin", "security", "ai", "vcs", "cli", "cache", "license-policy", "policy", "webhooks", "reports", "diagnostics", "config-transfer")) {
            page.navigate(url("/settings?tab="+tab+"&lang=ja")); page.waitForTimeout(150);
            page.keyboard().press("Tab");
            assertThat(page.evaluate("() => document.activeElement !== document.body")).as(tab).isEqualTo(true);
            page.keyboard().press("Escape");
            if (!(Boolean) page.evaluate("() => document.documentElement.scrollWidth <= innerWidth + 1")) failures.add(tab + " document overflows viewport");
            var serious=runAxeScan().getViolations().stream().filter(v -> "serious".equals(v.getImpact()) || "critical".equals(v.getImpact())).toList();
            for (var violation:serious) for(var node:violation.getNodes()) failures.add(tab+" "+violation.getId()+" "+node.getHtml());
            result.append(tab).append(" seriousCritical=").append(serious.size()).append(" layout=")
                    .append(page.evaluate("() => ({viewport:innerWidth,document:document.documentElement.scrollWidth})")).append('\n');
        }
        Files.writeString(report,result+String.join("\n",failures));
        assertThat(failures).isEmpty();
    }

    private void login(String email) {
        page.navigate(url("/login")); page.fill("#login-email",email); page.fill("#login-password",TEST_PASSWORD);
        page.click("button[type=submit]"); page.waitForURL(value -> !value.contains("/login"));
    }
}
