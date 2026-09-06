package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.service.project.ProjectService;
import com.salkcoding.oswl.service.vulnerability.SecurityCenterService;
import com.salkcoding.oswl.service.reporting.RiskTrendService;
import com.salkcoding.oswl.service.reporting.SbomExportService;
import com.salkcoding.oswl.service.reporting.SarifExportService;
import com.salkcoding.oswl.service.scan.*;
import com.salkcoding.oswl.service.org.OrgDashboardService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in local H2 measurements. No remote enrichment or production database is used. */
@EnabledIfEnvironmentVariable(named = "OSWL_PERFORMANCE", matches = "true")
@org.springframework.context.annotation.Import(JdbcBudgetProbe.class)
@org.springframework.test.annotation.DirtiesContext
class PerformanceBudgetUiTest extends UiTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired EntityManagerFactory emf;
    @Autowired ProjectService projectService;
    @Autowired SecurityCenterService security;
    @Autowired RiskTrendService trend;
    @Autowired VersionDiffService diff;
    @Autowired ComponentDetailService detail;
    @Autowired OrgDashboardService org;
    @Autowired ScanArchivingService archive;
    @Autowired SbomExportService sbom;
    @Autowired SarifExportService sarif;
    @Autowired com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService snapshot;
    private String sessionCookie;
    private final Path report = Path.of("build/reports/performance/local.csv");

    @Test void measureLargeReadsExportsAndLongLivedPage() throws Exception {
        Files.createDirectories(report.getParent());
        Files.writeString(report.resolveSibling("archive-pool.txt"), "");
        Files.writeString(report.resolveSibling("snapshot-bundles.txt"), "");
        Files.writeString(report, "operation,n,p50_ms,p95_ms,statements_per_call,hql_result_rows_per_call,entities_per_call,sampled_peak_heap_bytes,jdbc_rows_per_call,jdbc_ms_per_call\n");
        var principal = new OswlUserPrincipal(1L, TEST_EMAIL, "unused", "Performance", true, true,
                List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        try {
            loginAsTestAdmin();
            sessionCookie = context.cookies().stream().map(c -> c.name + "=" + c.value).collect(java.util.stream.Collectors.joining("; "));
            for (int size : List.of(5000, 50000)) {
                Project project = projects.save(Project.builder().name("Performance-" + size).build());
                ScanResult old = newScan(project, "1.0", LocalDateTime.now().minusWeeks(3));
                seedComponents(old.getId(), size, size * 10L);
                ScanResult latest = newScan(project, "2.0", LocalDateTime.now());
                jdbc.update("insert into scan_components(scan_result_id,library_id,reachability,reviewed,ignored) "
                        + "select ?,library_id,'UNKNOWN',false,false from scan_components where scan_result_id=?", latest.getId(), old.getId());
                // Use a cached insight so version-diff timings cannot include external AI calls.
                jdbc.update("update scan_results set version_diff_ai_insight='fixture',version_diff_from_scan_id=? where id=?", old.getId(), latest.getId());
                Long id = project.getId();
                Long component = jdbc.queryForObject("select min(id) from scan_components where scan_result_id=?", Long.class, latest.getId());
                measure(size + "-projects", 20, projectService::findAll);
                measure(size + "-security", 20, () -> security.populateIndexModel(id, latest.getId(), new ExtendedModelMap()));
                measure(size + "-trend-2-scans", 20, () -> trend.populateModel(id, latest.getId(), new ExtendedModelMap()));
                measure(size + "-detail", 20, () -> detail.populateModel(id, component, new ExtendedModelMap()));
                measure(size + "-diff", 5, () -> diff.populateModel(id, old.getId(), latest.getId(), new ExtendedModelMap()));
                measure(size + "-org", 5, () -> org.populateModel(new ExtendedModelMap()));
                measure(size + "-sbom", 3, () -> assertThat(sbom.export(id, SbomExportService.Format.JSON).content()).contains("bomFormat"));
                measure(size + "-sarif", 3, () -> assertThat(sarif.export(id).content()).contains("2.1.0"));
                var bundle = new java.util.concurrent.atomic.AtomicReference<byte[]>();
                measure(size + "-snapshot-export", 1, () -> bundle.set(snapshot.exportBundle()));
                Files.writeString(report.resolveSibling("snapshot-bundles.txt"), size + " components; compressed_bytes=" + bundle.get().length + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                measure(size + "-snapshot-import", 1, () -> assertThat(snapshot.importBundle(new java.io.ByteArrayInputStream(bundle.get())).totalRecords()).isPositive());
                bundle.set(null);
                measureBrowser(id, size);
                for (int i = 3; i <= 100; i++) {
                    ScanResult history = newScan(project, "0." + i, LocalDateTime.now().minusDays(100 + i));
                    jdbc.update("insert into scan_components(scan_result_id,library_id,reachability,reviewed,ignored) "
                            + "select ?,library_id,'UNKNOWN',false,false from scan_components where scan_result_id=? limit 50", history.getId(), old.getId());
                    if (i == 10) measure(size + "-trend-10-scans", 20, () -> trend.populateModel(id, latest.getId(), new ExtendedModelMap()));
                }
                measure(size + "-trend-100-scans", 20, () -> trend.populateModel(id, latest.getId(), new ExtendedModelMap()));
                page.navigate(url("/projects/" + id + "/security-center"));
                recordDom(report.resolveSibling("browser-" + size + ".txt"), "100-scan-versions");
                var before = new ExtendedModelMap();
                trend.populateModel(id, old.getId(), before);
                // The full export cost remains visible, including dependency-path reads.
                measure(size + "-archive-export", 1, () -> assertThat(archive.exportPendingArchive(id, 1)).isNotEmpty());
                measure(size + "-archive-delete", 1, () -> archive.archiveProject(id, 1));
                var after = new ExtendedModelMap();
                trend.populateModel(id, old.getId(), after);
                assertThat(after.getAttribute("chartSecHigh")).isEqualTo(before.getAttribute("chartSecHigh"));
                assertThat(jdbc.queryForObject("select count(*) from scan_components where scan_result_id=?", Long.class, old.getId())).isZero();
                assertThat(jdbc.queryForObject("select count(*) from scan_components where scan_result_id=?", Long.class, latest.getId())).isEqualTo((long) size);
                if (size == 50000) {
                    for (int i = 0; i < 98; i++) {
                        Project peer = projects.save(Project.builder().name("Performance-peer-" + i).build());
                        ScanResult peerScan = newScan(peer, "1.0", LocalDateTime.now());
                        jdbc.update("insert into scan_components(scan_result_id,library_id,reachability,reviewed,ignored) "
                                + "select ?,library_id,'UNKNOWN',false,false from scan_components where scan_result_id=? limit 50", peerScan.getId(), latest.getId());
                    }
                    measure("100-projects-cards", 20, projectService::findAll);
                    measure("100-projects-org", 20, () -> org.populateModel(new ExtendedModelMap()));
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ScanResult newScan(Project project, String version, LocalDateTime time) {
        var scan = ScanResult.builder().project(project).version(version).status(ScanStatus.COMPLETED).build();
        scan.setScannedAt(time);
        return scans.save(scan);
    }

    private void seedComponents(Long scanId, int size, long offset) {
        jdbc.update("insert into libraries(id,name,version,ecosystem,license_name,license_status,malicious,typosquat_risk) "
                + "select x+?,concat('perf-lib-',x+?),'1.0','NPM','MIT',case when mod(x,7)=0 then 'RESTRICTED' else 'PERMITTED' end,false,false from system_range(1,?)", offset, offset, size);
        jdbc.update("insert into library_cves(library_id,cve_id,severity,cvss_score,severity_conflict,kev_listed) "
                + "select x+?,concat('CVE-PERF-',x+?),'HIGH',7.5,false,mod(x,5)=0 from system_range(1,?)", offset, offset, size);
        jdbc.update("insert into scan_components(scan_result_id,library_id,reachability,reviewed,ignored) "
                + "select ?,x+?,'UNKNOWN',false,false from system_range(1,?)", scanId, offset, size);
    }

    private void measure(String name, int count, Runnable action) throws Exception {
        // Mutations must execute exactly once, without a warm-up.
        if (count > 1) {
            JdbcBudgetProbe.start(name, true);
            try { action.run(); } finally { JdbcBudgetProbe.CURRENT.remove(); }
        }
        var jdbcSample = JdbcBudgetProbe.start(name, false);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong();
        var pool = jdbc.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
        AtomicLong active = new AtomicLong(), pending = new AtomicLong();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                active.accumulateAndGet(pool.getActiveConnections(), Math::max);
                pending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        List<Long> requestTimes = Collections.synchronizedList(new ArrayList<>());
        AtomicLong requestFailures = new AtomicLong();
        Thread probe = !name.contains("archive-") ? null : Thread.ofVirtual().start(() -> {
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                while (sampling.get()) {
                    long start = System.nanoTime();
                    try {
                        var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url("/projects")))
                                .header("Cookie", sessionCookie).timeout(java.time.Duration.ofSeconds(10)).build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() == 200) requestTimes.add((System.nanoTime()-start)/1_000_000);
                        else requestFailures.incrementAndGet();
                    } catch (Exception e) { requestFailures.incrementAndGet(); }
                }
            }
        });
        List<Long> times = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                long start = System.nanoTime();
                action.run();
                times.add((System.nanoTime() - start) / 1_000_000);
            }
        } finally {
            sampling.set(false);
            JdbcBudgetProbe.CURRENT.remove();
            sampler.join(1000);
            if (probe != null) {
                probe.join(11000);
                requestTimes.sort(null);
                Files.writeString(report.resolveSibling("archive-pool.txt"), name + " active=" + active + " pending=" + pending
                        + " request_n=" + requestTimes.size() + " request_p95_ms=" + (requestTimes.isEmpty() ? "unavailable" : percentile(requestTimes,.95))
                        + " request_failures=" + requestFailures + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (!times.isEmpty()) {
                times.sort(null);
                long rows = Arrays.stream(stats.getQueries()).map(stats::getQueryStatistics).mapToLong(q -> q.getExecutionRowCount()).sum();
                Files.writeString(report, String.format(Locale.ROOT, "%s,%d,%d,%d,%.1f,%.1f,%.1f,%d,%.1f,%.3f%n", name, times.size(),
                        percentile(times, .5), percentile(times, .95), (double) jdbcSample.statements / times.size(),
                        (double) rows / times.size(), (double) stats.getEntityLoadCount() / times.size(), peak.get(),
                        (double) jdbcSample.rows / times.size(), jdbcSample.nanos / 1_000_000.0 / times.size()), StandardOpenOption.APPEND);
            }
        }
    }

    private void measureBrowser(Long project, int size) throws Exception {
        String path = "/projects/" + project + "/security-center";
        page.navigate(url(path));
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            page.navigate(url(path));
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        times.sort(null);
        Path browserReport = report.resolveSibling("browser-" + size + ".txt");
        Files.writeString(browserReport, "navigation n=20 p50=" + percentile(times,.5) + "ms p95=" + percentile(times,.95) + "ms\n");
        recordDom(browserReport, "initial");
        for (int i = 1; i <= 10; i++) {
            page.locator("button[\\@click='loadMoreRows()']").click();
            final int expected = (i + 1) * 100;
            page.waitForFunction("n => document.querySelectorAll('.component-row').length === n", expected);
            recordDom(browserReport, "load-more-" + i);
        }
        page.locator("label:has(input[x-model='filters.licRestricted'])").click();
        page.waitForFunction("() => document.querySelectorAll('.component-row').length === 100");
        recordDom(browserReport, "filtered");
        assertThat(page.locator(".component-row").count()).isEqualTo(100);
    }

    private void recordDom(Path file, String stage) throws Exception {
        Object metrics = page.evaluate("""
            () => ({nodes:document.querySelectorAll('*').length, rows:document.querySelectorAll('.component-row').length,
                heap:performance.memory?.usedJSHeapSize ?? null, requests:performance.getEntriesByType('resource').length,
                htmlBytes:performance.getEntriesByType('navigation')[0].decodedBodySize})
            """);
        Files.writeString(file, stage + " " + metrics + "\n", StandardOpenOption.APPEND);
        var cdp = context.newCDPSession(page);
        try { Files.writeString(file, "heap-exact " + cdp.send("Runtime.getHeapUsage") + "\n", StandardOpenOption.APPEND); }
        finally { cdp.detach(); }
    }

    @Test void measureRepositoryBrowser() throws Exception {
        Files.createDirectories(report.getParent());
        loginAsTestAdmin();
        List<String> errors = new ArrayList<>();
        page.onPageError(errors::add);
        page.navigate(url("/projects"));
        if (page.locator("#onboarding-dialog-title").isVisible()) page.keyboard().press("Escape");
        page.locator("button[\\@click^=\"openPanel = 'quickImport'\"]").click();
        page.waitForFunction("() => { const e=document.querySelector('[x-data=\"quickImportPage()\"]'); if (!window.Alpine || !e) return false; const state=Alpine.$data(e); return state.repoBrowsers && state.connectionsByProvider && state.loadingConnections === false; }");
        Path out = report.resolveSibling("repo-browser.txt");
        Files.writeString(out, "Synthetic client-side repository arrays; provider API pagination/network is not measured.\n");
        for (int size : List.of(5000, 50000)) {
            Object result = page.evaluate("""
                async size => {
                    const state=Alpine.$data(document.querySelector('[x-data="quickImportPage()"]'));
                    state.connectionsByProvider.GITHUB={provider:'GITHUB',vcsUsername:'fixture'};
                    state.repoBrowsers.GITHUB.repos=Array.from({length:size},(_,i)=>({name:'repo-'+i,fullName:'fixture/repo-'+i,
                        cloneUrl:'https://example.invalid/fixture/repo-'+i,defaultBranch:'main',isPrivate:false}));
                    await Alpine.nextTick();
                    const times=[];
                    for(let i=0;i<20;i++) {
                        const start=performance.now();
                        state.repoBrowsers.GITHUB.search='repo-'+(i%10);
                        state.onBrowserSearch('GITHUB');
                        await Alpine.nextTick();
                        times.push(performance.now()-start);
                    }
                    times.sort((a,b)=>a-b);
                    return {size,n:20,p50:times[9],p95:times[18],nodes:document.querySelectorAll('*').length,
                        visibleRepos:document.querySelectorAll('[x-text="repo.fullName"]').length,
                        resourceRequests:performance.getEntriesByType('resource').length};
                }
                """, size);
            Files.writeString(out, result + "\n", StandardOpenOption.APPEND);
            assertThat(page.locator("[x-text='repo.fullName']").count()).isLessThanOrEqualTo(10);
        }
        assertThat(errors).isEmpty();
    }

    private static long percentile(List<Long> sorted, double quantile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1));
    }
}
