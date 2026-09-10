package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class SettingsFailureUiTest extends UiTestBase {
    static Stream<Arguments> settings() {
        return Stream.of(
            Arguments.of("reports", "/api/settings/report-branding", "{\"companyName\":\"Saved company\",\"headerText\":\"Saved header\",\"showCoverPage\":true}"),
            Arguments.of("webhooks", "/api/settings/webhooks", "{\"provider\":\"TEAMS\",\"enabled\":true,\"notifyNewCve\":false}"),
            Arguments.of("cache", "/api/settings/cache", "[{\"cacheKey\":\"OSV\",\"ttlHours\":24}]")
        );
    }

    @ParameterizedTest @MethodSource("settings")
    void initialFailuresBlockWritesAndRetryRestoresSavedValues(String tab, String endpoint, String saved) {
        AtomicInteger failure = new AtomicInteger(500), writes = new AtomicInteger(), reads = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        page.route("**" + endpoint, route -> {
            if (route.request().method().equals("PUT")) {
                writes.incrementAndGet(); body.set(route.request().postData());
                route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody("{}"));
            } else {
                reads.incrementAndGet();
                if (failure.get() == -1) route.abort("failed");
                else route.fulfill(new Route.FulfillOptions().setStatus(failure.get()).setContentType("application/json")
                        .setBody(failure.get() == 200 ? saved : "{}"));
            }
        });
        loginAsTestAdmin();
        for (int status : List.of(500, 403, -1)) {
            failure.set(status); reads.set(0);
            page.navigate(url("/settings?tab=" + tab + "&lang=en"));
            page.waitForFunction("tab => { const e=document.querySelector('[x-data=\"'+tab+'Tab()\"]'); return e && Alpine.$data(e).apiError && !Alpine.$data(e).loading; }", tab);
            assertThat(reads.get()).as("one initialization for %s", tab).isEqualTo(1);
            assertThat(page.locator("button[\\@click='save()']").isDisabled()).isTrue();
            page.evaluate("tab => Alpine.$data(document.querySelector('[x-data=\"'+tab+'Tab()\"]')).save()", tab);
            assertThat(writes.get()).isZero();
        }
        failure.set(200);
        page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Retry")).first().click();
        page.waitForFunction("tab => Alpine.$data(document.querySelector('[x-data=\"'+tab+'Tab()\"]')).loaded", tab);
        page.waitForResponse(r -> r.request().method().equals("PUT"), () -> page.locator("button[\\@click='save()']").click());
        assertThat(writes.get()).isEqualTo(1);
        assertThat(body.get()).contains(tab.equals("reports") ? "Saved company" : tab.equals("webhooks") ? "TEAMS" : "86400");
    }

    @Test void failedAndOverlappingSavesPreserveNewerEditsAndBeforeUnloadWarning() {
        AtomicReference<Route> pending = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        page.route("**/api/settings/report-branding", route -> {
            if (route.request().method().equals("PUT")) { writes.incrementAndGet(); pending.set(route); }
            else route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody("{\"companyName\":\"Stored\"}"));
        });
        loginAsTestAdmin(); page.navigate(url("/settings?tab=reports&lang=en"));
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).loaded");
        var input = page.locator("input[x-model='form.companyName']");
        input.fill("First edit");
        page.locator("button[\\@click='save()']").click();
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).saving");
        input.fill("Newer edit");
        page.evaluate("() => Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).save()");
        assertThat(writes.get()).isEqualTo(1);
        pending.get().fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody("{}"));
        page.waitForFunction("() => !Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).saving");
        assertThat(input.inputValue()).isEqualTo("Newer edit");
        assertThat(page.evaluate("() => OswlDirty.isDirty()")).isEqualTo(true);
        page.locator("button[\\@click='save()']").click();
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).saving");
        pending.get().fulfill(new Route.FulfillOptions().setStatus(500).setBody("failed"));
        page.waitForFunction("() => !!Alpine.$data(document.querySelector('[x-data=\"reportsTab()\"]')).apiError");
        assertThat(input.inputValue()).isEqualTo("Newer edit");
        assertThat(page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Retry")).isVisible()).isFalse();
        AtomicInteger warnings = new AtomicInteger();
        page.onDialog(dialog -> { assertThat(dialog.type()).isEqualTo("beforeunload"); warnings.incrementAndGet(); dialog.dismiss(); });
        input.click();
        page.close(new com.microsoft.playwright.Page.CloseOptions().setRunBeforeUnload(true));
        page.waitForTimeout(100);
        assertThat(warnings.get()).isEqualTo(1);
        assertThat(page.isClosed()).isFalse();
    }
}
