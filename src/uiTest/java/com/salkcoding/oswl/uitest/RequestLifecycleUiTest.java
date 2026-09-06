package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Route;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class RequestLifecycleUiTest extends UiTestBase {
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired ScanComponentRepository components;
    @Autowired LibraryRepository libraries;

    private Long seed(int count) {
        var project = projects.save(Project.builder().name("Request-" + UUID.randomUUID()).build());
        var scan = scans.save(ScanResult.builder().project(project).version("1.0").status(ScanStatus.COMPLETED).build());
        var library = libraries.save(Library.builder().name("request-fixture-" + UUID.randomUUID())
                .version("1.0").ecosystem("NPM").licenseStatus(LicenseStatus.PERMITTED).build());
        List<ScanComponent> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(ScanComponent.builder().scanResult(scan).library(library).build());
        components.saveAll(rows);
        return project.getId();
    }

    @Test void failedInitialSettingsLoadCannotSaveDefaultsAndRetryRestoresEditing() {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        page.route("**/api/settings/security", route -> {
            if (route.request().method().equals("PUT")) {
                writes.incrementAndGet(); bodies.add(route.request().postData());
                route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody("{}"));
            } else if (fail.get()) route.fulfill(new Route.FulfillOptions().setStatus(500).setBody("failed"));
            else route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json")
                    .setBody("{\"mailMode\":\"SMTP\",\"twoFaMode\":\"EMAIL_OTP\",\"mail\":{\"host\":\"smtp.example.test\",\"port\":587}}"));
        });
        loginAsTestAdmin();
        page.navigate(url("/settings?tab=security&lang=en"));
        page.waitForFunction("() => { const e=document.querySelector('[x-data=\"securityTab()\"]'); return e && Alpine.$data(e).apiError; }");
        var mailSave = page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Save")).first();
        assertThat(mailSave.isDisabled()).isTrue();
        page.evaluate("() => { const s=Alpine.$data(document.querySelector('[x-data=\"securityTab()\"]')); return Promise.all([s.saveMail(),s.saveTwoFa()]); }");
        assertThat(writes).hasValue(0);
        fail.set(false);
        page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Retry")).click();
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"securityTab()\"]')).loaded");
        assertThat(mailSave.isEnabled()).isTrue();
        page.waitForResponse(r -> r.request().method().equals("PUT"), mailSave::click);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).contains("\"mailMode\":\"SMTP\"");
    }

    @Test void filteredAndAppendedRowsStillLoadTheirDetailPanel() {
        Long project = seed(101);
        List<String> errors = new ArrayList<>();
        page.onPageError(errors::add);
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project + "/security-center?lang=en"));
        page.waitForFunction("() => window.Alpine && Alpine.$data(document.body).rowsLoaded");
        assertThat(page.locator("a.component-row").count()).isEqualTo(100);
        page.waitForResponse(r -> r.url().contains("/security-center/rows?"),
                () -> page.locator("input[x-model='searchQuery']").fill("request-fixture"));
        page.waitForFunction("() => !Alpine.$data(document.body).rowsLoading");
        page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Load more")).click();
        page.waitForFunction("() => document.querySelectorAll('a.component-row').length === 101");
        var row = page.locator("a.component-row").last();
        String id = row.getAttribute("data-comp-id");
        var response = page.waitForResponse(r -> r.url().contains("/components/" + id), row::click);
        assertThat(response.status()).isEqualTo(200);
        page.waitForFunction("() => document.querySelector('#slideout-content').textContent.includes('request-fixture')");
        assertThat(errors).isEmpty();
    }

    @Test void lateResponseBodyCannotReplaceLatestRowsAndErrorsAreClassified() {
        Long project = seed(0);
        loginAsTestAdmin();
        page.navigate(url("/projects/" + project + "/security-center?lang=en"));
        page.waitForFunction("() => window.Alpine && Alpine.$data(document.body).rowsLoaded");
        page.evaluate("""
                async () => {
                    const original = window.fetch;
                    let call = 0;
                    window.fetch = (url, options) => {
                        if (!String(url).includes('/security-center/rows?')) return original(url,options);
                        const first = ++call === 1;
                        return Promise.resolve(new Response(new ReadableStream({start(controller) {
                            setTimeout(() => {
                                controller.enqueue(new TextEncoder().encode('<div data-total-count="0" data-has-more="false">'+(first?'older':'newer')+'</div>'));
                                controller.close();
                            }, first ? 150 : 0);
                        }})));
                    };
                    try {
                        const state = Alpine.$data(document.body);
                        await Promise.all([state.refetchRows(),state.refetchRows()]);
                    } finally { window.fetch = original; }
                }
                """);
        assertThat(page.locator("#component-rows-container").textContent()).contains("newer").doesNotContain("older");
        page.route("**/verification/http/**", route -> {
            String path = route.request().url();
            if (path.endsWith("timeout")) return;
            route.fulfill(new Route.FulfillOptions().setStatus(Integer.parseInt(path.substring(path.lastIndexOf('/') + 1))).setBody("error"));
        });
        Object kinds = page.evaluate("""
                async () => {
                    const result = {};
                    for (const status of ['401','403','500','timeout']) {
                        try { await OswlHttp.text('/verification/http/'+status, {timeoutMs: status === 'timeout' ? 30 : 30000}); }
                        catch(error) { result[status] = error.kind; }
                    }
                    return result;
                }
                """);
        assertThat(kinds).isEqualTo(Map.of("401", "unauthenticated", "403", "forbidden", "500", "server", "timeout", "timeout"));
    }
}
