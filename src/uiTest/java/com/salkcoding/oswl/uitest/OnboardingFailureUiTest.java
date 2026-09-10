package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Route.FulfillOptions;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class OnboardingFailureUiTest extends UiTestBase {
    @Test void providerAndWebhookFailuresKeepFormsRetryableAndSuccessClearsSecrets() {
        loginAsTestAdmin();
        for (String provider : new String[]{"GITHUB", "GITLAB", "BITBUCKET"}) {
            AtomicInteger requests = new AtomicInteger();
            page.route("**/api/settings/vcs", route -> route.fulfill(new FulfillOptions()
                    .setStatus(requests.incrementAndGet() == 1 ? 403 : 200)
                    .setContentType("application/json").setBody("{\"error\":\"Fixture permission denied\"}")));
            page.navigate(url("/onboarding?lang=en"));
            page.evaluate("provider => { const s=Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')); s.step=2; s.done.repoConnected=false; s.vcsProvider=provider; }", provider);
            page.fill("#onb-vcs-token", "fixture-token-only");
            if (provider.equals("BITBUCKET")) page.fill("#onb-vcs-username", "fixture-user");
            page.locator("button[\\@click='connectVcs()']").click();
            page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).vcsError !== null");
            assertThat(page.locator("[x-text=vcsError]").innerText()).contains("Fixture permission denied");
            assertThat(page.locator("#onb-vcs-token").inputValue()).isEqualTo("fixture-token-only");
            page.locator("button[\\@click='connectVcs()']").click();
            page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).done.repoConnected");
            assertThat(page.locator("#onb-vcs-token").inputValue()).isEmpty();
            assertThat(requests.get()).isEqualTo(2);
            page.unroute("**/api/settings/vcs");
        }
        AtomicInteger hooks = new AtomicInteger();
        page.route("**/api/settings/webhooks", route -> {
            if (hooks.incrementAndGet() == 1) route.abort();
            else route.fulfill(new FulfillOptions().setStatus(200).setContentType("application/json").setBody("{}"));
        });
        page.evaluate("() => { const s=Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')); s.step=4; s.done.notificationsReady=false; s.hookUrl='https://example.invalid/fixture'; }");
        page.locator("button[\\@click='saveWebhook()']").click();
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).hookError !== null");
        assertThat(page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).done.notificationsReady")).isEqualTo(false);
        page.locator("button[\\@click='saveWebhook()']").click();
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).done.notificationsReady");
        assertThat(page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=onboardingWizard]')).hookUrl")).isEqualTo("");
    }
}
