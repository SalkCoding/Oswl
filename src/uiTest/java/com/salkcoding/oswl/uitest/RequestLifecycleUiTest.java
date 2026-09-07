package com.salkcoding.oswl.uitest;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.Page;
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
        page.locator("label").filter(new com.microsoft.playwright.Locator.FilterOptions()
                .setHas(page.locator("input[name=selectedComponent]"))).first().click();
        page.waitForFunction("() => Alpine.$data(document.body).selectedComponents.length === 1");
        page.waitForResponse(r -> r.url().contains("/security-center/rows?"),
                () -> page.locator("input[x-model='searchQuery']").fill("request-fixture"));
        page.waitForFunction("() => !Alpine.$data(document.body).rowsLoading");
        assertThat(page.evaluate("() => Alpine.$data(document.body).selectedComponents")).isEqualTo(List.of());
        assertThat(page.evaluate("() => Alpine.$data(document.body).selectAll")).isEqualTo(false);
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

    @Test void bulkFailureRetainsSelectionAndRepeatedActionsSendOneRequest() {
        Long project=seed(2);loginAsTestAdmin();
        page.navigate(url("/projects/"+project+"/security-center?lang=en"));
        page.waitForFunction("() => Alpine.$data(document.body).rowsLoaded");
        page.locator("label").filter(new com.microsoft.playwright.Locator.FilterOptions().setHas(page.locator("input[name=selectedComponent]"))).first().click();
        page.locator("button[aria-controls=sc-bulk-menu]").click();
        page.waitForFunction("() => getComputedStyle(document.querySelector('#sc-bulk-menu')).opacity === '1'");
        assertThat(runAxeScan().getViolations().stream().filter(v->List.of("serious","critical").contains(v.getImpact())).map(v->v.getId()+": "+v.getNodes().stream().map(n->n.getHtml()).toList()).toList()).isEmpty();
        page.evaluate("""
            () => {
                window.__bulkCalls=0;window.__bulkAlerts=0;window.__realFetch=window.fetch;
                window.alert=()=>window.__bulkAlerts++;
                window.fetch=(url,options)=>{
                    if(!String(url).endsWith('/bulk-status'))return window.__realFetch(url,options);
                    window.__bulkCalls++;
                    return new Promise(resolve=>setTimeout(()=>resolve(new Response('{}',{status:500})),100));
                };
            }
            """);
        page.evaluate("() => {const s=Alpine.$data(document.body);return Promise.all([s.applyBulkAction('reviewed'),s.applyBulkAction('reviewed')]);}");
        assertThat(page.evaluate("() => window.__bulkCalls")).isEqualTo(1);
        assertThat(page.evaluate("() => window.__bulkAlerts")).isEqualTo(1);
        assertThat(page.evaluate("() => Alpine.$data(document.body).selectedComponents.length")).isEqualTo(1);
        page.evaluate("() => window.fetch=window.__realFetch");
        page.evaluate("() => Alpine.$data(document.body).applyBulkAction('reviewed')");
        assertThat(page.evaluate("() => Alpine.$data(document.body).selectedComponents.length")).isEqualTo(0);
    }

    @Test void failedDetailRetriesAndEscapeReturnsFocusToItsRow() {
        Long project=seed(1);loginAsTestAdmin();
        page.navigate(url("/projects/"+project+"/security-center?lang=en"));
        var row=page.locator("a.component-row").first();String id=row.getAttribute("data-comp-id");
        AtomicBoolean fail=new AtomicBoolean(true);
        page.route("**/projects/"+project+"/components/"+id,route->{
            if(fail.get())route.fulfill(new Route.FulfillOptions().setStatus(500).setBody("failed"));else route.resume();
        });
        row.focus();row.click();page.locator("#slideout-retry").waitFor();
        assertThat(runAxeScan().getViolations().stream().filter(v->List.of("serious","critical").contains(v.getImpact())).map(v->v.getId()+": "+v.getNodes().stream().map(n->n.getHtml()).toList()).toList()).isEmpty();
        fail.set(false);page.locator("#slideout-retry").click();
        page.waitForFunction("() => document.querySelector('#slideout-content').textContent.includes('request-fixture')");
        page.keyboard().press("Escape");
        page.waitForFunction("() => !Alpine.$data(document.body).componentPanelOpen");
        assertThat(page.evaluate("() => document.activeElement.dataset.compId")).isEqualTo(id);
    }

    @Test void allFilterQueriesHandleEmptyResultsAndFailedRefreshCanRetry() {
        Long project=seed(0);loginAsTestAdmin();
        page.navigate(url("/projects/"+project+"/security-center?lang=en"));
        page.waitForFunction("() => Alpine.$data(document.body).rowsLoaded");
        Object empty=page.evaluate("""
            async () => {
                const state=Alpine.$data(document.body), result=[];
                for(const key of Object.keys(state.filters)) {
                    const parameters=state.buildRowsQueryParams(0);parameters.set(key,'true');
                    const response=await fetch('/projects/'+document.body.dataset.projectId+'/security-center/rows?'+parameters);
                    const body=await response.text();
                    result.push(response.ok && body.includes('data-total-count="0"'));
                }
                return result.every(Boolean);
            }
            """);
        assertThat(empty).isEqualTo(true);
        AtomicBoolean fail=new AtomicBoolean(true);
        page.route("**/security-center/rows?**",route->{if(fail.get())route.fulfill(new Route.FulfillOptions().setStatus(500).setBody("failed"));else route.resume();});
        page.evaluate("() => Alpine.$data(document.body).refetchRows()");
        page.locator("#component-rows-container").waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#component-rows-container").isVisible()).isFalse();
        assertThat(page.locator("[role=alert]").filter(new com.microsoft.playwright.Locator.FilterOptions().setHas(page.locator("button"))).isVisible()).isTrue();
        assertThat(runAxeScan().getViolations().stream().filter(v->List.of("serious","critical").contains(v.getImpact())).map(v->v.getId()+": "+v.getNodes().stream().map(n->n.getHtml()).toList()).toList()).isEmpty();
        fail.set(false);
        page.locator("[role=alert] button").click();
        page.waitForFunction("() => !Alpine.$data(document.body).rowsLoading && !Alpine.$data(document.body).rowsError");
        assertThat(page.locator("#component-rows-container").isVisible()).isTrue();
    }

    @Test void nestedSearchClosesOnlyTopDialogAndRestoresOuterTrap() {
        Long project=seed(1);loginAsTestAdmin();
        page.navigate(url("/projects/"+project+"/security-center?lang=en"));
        var row=page.locator("a.component-row").first();String id=row.getAttribute("data-comp-id");row.focus();row.click();
        page.waitForFunction("() => document.querySelector('#slideout-content').textContent.includes('request-fixture')");
        page.keyboard().press("Control+k");
        page.locator("input[x-ref=searchInput]").waitFor();
        page.waitForFunction("() => document.activeElement.matches('input[x-ref=searchInput]')");
        page.keyboard().press("Escape");
        assertThat(page.evaluate("() => Alpine.$data(document.body).componentPanelOpen")).isEqualTo(true);
        assertThat(page.evaluate("() => document.activeElement.closest('[role=dialog]')?.contains(document.querySelector('#slideout-content'))")).isEqualTo(true);
        assertThat(page.evaluate("() => document.querySelector('#component-rows-container').closest('[inert]') !== null")).isEqualTo(true);
        page.keyboard().press("Escape");
        page.waitForFunction("() => !Alpine.$data(document.body).componentPanelOpen");
        assertThat(page.evaluate("() => document.activeElement.dataset.compId")).isEqualTo(id);
    }
    @Test void clearingOrClosingSearchRejectsAnOlderResponseBody() {
        loginAsTestAdmin(); page.navigate(url("/projects?lang=en"));
        page.keyboard().press("Control+k");
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"oswlSearchPalette()\"]')).open");
        assertThat(page.evaluate("""
            async () => {
                const state=Alpine.$data(document.querySelector('[x-data="oswlSearchPalette()"]'));
                const original=window.fetch;
                try {
                    for (const action of ['clear','close']) {
                        state.query='old'; await Alpine.nextTick(); clearTimeout(state.debounceTimer);
                        let release;
                        window.fetch=async()=>({ok:true,json:()=>new Promise(resolve=>release=resolve)});
                        const pending=state.runSearch('old');
                        await Promise.resolve();
                        if (action==='clear') {state.query='';await Alpine.nextTick();}
                        else state.closePalette();
                        release({projects:{items:[{id:1,name:'stale'}]}});await pending;
                        if(state.flatItems.length || state.searched) return false;
                    }
                    return true;
                } finally {window.fetch=original;}
            }
            """)).isEqualTo(true);
    }


    @Test void settingsInitializationDoesNotCountAsAnEditButUserInputDoes() {
        page.route("**/api/settings/cache", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("application/json")
                .setBody("[{\"cacheKey\":\"DEPS_DEV\",\"ttlHours\":48}]")));
        loginAsTestAdmin();
        page.navigate(url("/settings?tab=cache&lang=en"));
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"cacheTab()\"]')).loaded");
        assertThat(page.evaluate("() => window.OswlDirty.isDirty()")).isEqualTo(false);
        page.locator("input[type=number]").fill("3");
        assertThat(page.evaluate("() => window.OswlDirty.isDirty()")).isEqualTo(true);
    }

    @Test void invalidAiParametersAreRejectedBeforeSending() {
        AtomicInteger writes = new AtomicInteger();
        page.route("**/api/settings/ai", route -> {
            if (route.request().method().equals("PUT")) writes.incrementAndGet();
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType("application/json")
                    .setBody("{\"provider\":\"LOCAL\",\"active\":true,\"modelName\":\"fixture\",\"activeProviderKind\":\"LOCAL\"}"));
        });
        loginAsTestAdmin();
        page.navigate(url("/settings?tab=ai&section=context&lang=en"));
        page.waitForFunction("() => Alpine.$data(document.querySelector('[x-data=\"aiTab()\"]'))._contextLoaded");
        page.getByPlaceholder("Default: 0.15").fill("99");
        page.getByPlaceholder("Default: 1200").fill("8193");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Save").setExact(true)).click();
        assertThat(writes).hasValue(0);
        assertThat(page.locator("[x-text=apiError]").innerText()).contains("256");
    }

    @Test void emptyArchiveExportExplainsWhyNoFileIsDownloaded() {
        page.route("**/api/admin/projects", route -> route.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody("[{\"id\":1,\"name\":\"Archive fixture\"}]")));
        page.route("**/archive-scans/export", route -> route.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody("[]")));
        AtomicInteger downloads = new AtomicInteger();
        page.onDownload(download -> downloads.incrementAndGet());
        loginAsTestAdmin();
        page.navigate(url("/settings?tab=diagnostics&lang=en"));
        page.locator("select").selectOption("1");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Export older scans").setExact(true)).click();
        page.waitForCondition(() -> page.locator("[x-text='previewText()']").innerText().contains("No scans to export"));
        assertThat(downloads).hasValue(0);
    }
    @Test void archiveExportDownloadsTheReturnedScanDetails() throws Exception {
        page.route("**/api/admin/projects", route -> route.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody("[{\"id\":1,\"name\":\"Archive fixture\"}]")));
        page.route("**/archive-scans/export", route -> route.fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType("application/json").setBody("[{\"id\":42,\"components\":[{\"name\":\"immutable\"}]}]")));
        loginAsTestAdmin();
        page.navigate(url("/settings?tab=diagnostics&lang=en"));
        page.locator("select").selectOption("1");
        var download = page.waitForDownload(() -> page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Export older scans").setExact(true)).click());
        assertThat(download.suggestedFilename()).startsWith("oswl-scan-archive-export-p1-").endsWith(".json");
        assertThat(java.nio.file.Files.readString(download.path())).contains("immutable", "42");
    }
}
