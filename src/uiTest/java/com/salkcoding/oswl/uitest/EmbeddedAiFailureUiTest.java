package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedAiFailureUiTest extends UiTestBase {
    @Test void backgroundFailureSettlesAndRetryChangesProviderOnlyAfterRunning() {
        AtomicInteger attempts = new AtomicInteger();
        String stopped = "{\"binaryFound\":true,\"running\":false,\"downloading\":false,\"availableModels\":[],\"lastError\":\"Fixture checksum failure\"}";
        page.route("**/api/settings/ai/embedded", r -> r.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody(stopped)));
        page.route("**/api/settings/ai/embedded/start*", r -> r.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody(attempts.incrementAndGet() == 1
                        ? "{\"running\":false,\"downloading\":true,\"availableModels\":[]}"
                        : "{\"running\":true,\"success\":true,\"downloading\":false,\"availableModels\":[],\"activeModel\":\"fixture.gguf\"}")));
        loginAsTestAdmin(); page.navigate(url("/settings?tab=ai&lang=en"));
        page.evaluate("() => { const s=Alpine.$data(document.querySelector('[x-data^=aiTab]')); s.mode='EMBEDDED'; }");
        page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=aiTab]')).startEmbedded()");
        assertThat(page.locator("[x-text=embeddedError]").innerText()).contains("Fixture checksum failure");
        assertThat(page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=aiTab]')).embeddedBusy")).isEqualTo(false);
        page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=aiTab]')).startEmbedded()");
        assertThat(page.evaluate("() => Alpine.$data(document.querySelector('[x-data^=aiTab]')).activeProviderKind")).isEqualTo("EMBEDDED");
        assertThat(attempts.get()).isEqualTo(2);
    }
}
