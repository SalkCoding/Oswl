package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrganizationWorkflowUiTest extends UiTestBase {
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired com.salkcoding.oswl.auth.service.RoleTemplateService roles;

    @Test void inlineAccountCreationAndDuplicateFailureKeepFormUsable() {
        var role=new com.salkcoding.oswl.auth.dto.RoleTemplateRequest();
        role.setName("Inline viewer "+UUID.randomUUID()); role.setPermissions(Set.of("SECURITY_CENTER_VIEW")); roles.create(role);
        loginAsTestAdmin(); page.navigate(url("/onboarding?lang=en"));
        page.locator("#onboarding-team-role option").nth(1).waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
        String email="inline-"+UUID.randomUUID()+"@example.test";
        fillTeam(email);
        var response=page.waitForResponse(r->r.url().endsWith("/api/admin/users") && r.request().method().equals("POST"),()->page.locator("form[x-data='inlineTeamInvite()'] button[type=submit]").click());
        assertThat(response.status()).isEqualTo(200);
        page.waitForFunction("document.querySelector('#onboarding-team-password').value === ''");
        assertThat(users.findByEmail(email).orElseThrow().isMustChangePassword()).isTrue();
        fillTeam(email);
        page.waitForResponse(r->r.url().endsWith("/api/admin/users") && r.request().method().equals("POST"),()->page.locator("form[x-data='inlineTeamInvite()'] button[type=submit]").click());
        page.locator("form[x-data='inlineTeamInvite()'] [role=alert]").waitFor();
        assertThat(page.locator("#onboarding-team-email").isEnabled()).isTrue();
        assertNoSeriousOrCriticalAxeViolations();
    }

    @Test void summaryRendersThreeLanguagesOnNarrowScreenAndPrints() throws Exception {
        loginAsTestAdmin(); page.setViewportSize(390,844);
        for(String language:List.of("en","ko","ja")) {
            assertThat(page.navigate(url("/org-dashboard/summary?lang="+language)).status()).isEqualTo(200);
            assertThat(page.locator("h1").innerText()).isNotBlank();
            assertNoSeriousOrCriticalAxeViolations();
            assertThat((Boolean)page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")).isTrue();
        }
        Path output=Path.of("build/reports/remaining"); Files.createDirectories(output);
        page.setViewportSize(1100,1000);
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(output.resolve("organization-summary.png")).setFullPage(true));
        page.pdf(new com.microsoft.playwright.Page.PdfOptions().setPath(output.resolve("organization-summary.pdf")).setPreferCSSPageSize(true));
    }

    @Test void userWithoutOrganizationOrAdminPermissionCannotUseNewFlows() {
        var user=users.save(User.builder().email("org-denied-"+UUID.randomUUID()+"@example.test").passwordHash(passwords.encode(TEST_PASSWORD)).displayName("Reader").enabled(true).build());
        page.navigate(url("/login")); page.fill("#login-email",user.getEmail()); page.fill("#login-password",TEST_PASSWORD); page.click("button[type=submit]");
        page.waitForURL(u->!u.contains("/login"));
        assertThat(page.navigate(url("/org-dashboard/summary")).status()).isEqualTo(403);
        page.navigate(url("/onboarding"));
        assertThat(page.locator("form[x-data='inlineTeamInvite()']").count()).isZero();
        assertThat(context.request().get(url("/api/settings/scan-rules")).status()).isEqualTo(403);
    }

    private void fillTeam(String email) {
        page.fill("#onboarding-team-name","Inline fixture"); page.fill("#onboarding-team-email",email);
        page.fill("#onboarding-team-password",TEST_PASSWORD);
        page.selectOption("#onboarding-team-role",new SelectOption().setIndex(1));
    }
    private void assertNoSeriousOrCriticalAxeViolations() {
        assertThat(runAxeScan().getViolations().stream().filter(v -> Set.of("serious","critical").contains(v.getImpact())).toList()).isEmpty();
    }
}
