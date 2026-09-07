package com.salkcoding.oswl.service.diagnostics;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.client.VcsTokenValidator;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.dto.AiConnectionTestResult;
import com.salkcoding.oswl.dto.diagnostics.DiagnosticCheckResult;
import com.salkcoding.oswl.health.AiProviderHealthIndicator;
import com.salkcoding.oswl.health.DbHealthIndicator;
import com.salkcoding.oswl.health.DiskSpaceHealthIndicator;
import com.salkcoding.oswl.health.EmbeddedSidecarHealthIndicator;
import com.salkcoding.oswl.health.SnapshotFreshnessHealthIndicator;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Self-service diagnostics — reuses the {@code health/} indicators built for the readiness probe
 * where possible, and adds live outbound/credential checks that a passive readiness probe
 * deliberately skips (rate limits, latency). Every result is safe to paste into a support
 * request as-is: no secret, token, or password value is ever placed in a {@code detail} string.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticsService {

    private final DbHealthIndicator dbHealthIndicator;
    private final DiskSpaceHealthIndicator diskSpaceHealthIndicator;
    private final AiProviderHealthIndicator aiProviderHealthIndicator;
    private final EmbeddedSidecarHealthIndicator embeddedSidecarHealthIndicator;
    private final SnapshotFreshnessHealthIndicator snapshotFreshnessHealthIndicator;

    private final AiSettingRepository aiSettingRepository;
    private final AiAnalysisService aiAnalysisService;
    private final SecuritySettingService securitySettingService;
    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final VcsTokenValidator vcsTokenValidator;
    private final EncryptionService encryptionService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(5);

    public List<DiagnosticCheckResult> runAll(Long userId) {
        List<DiagnosticCheckResult> results = new ArrayList<>();
        results.add(fromHealth("db", "Database", dbHealthIndicator::health));
        results.add(fromHealth("disk", "Disk space", diskSpaceHealthIndicator::health));
        results.add(fromHealth("sidecar", "Embedded AI sidecar", embeddedSidecarHealthIndicator::health));
        results.add(airgapped ? fromHealth("snapshot", "Air-gapped snapshot freshness", snapshotFreshnessHealthIndicator::health)
                : new DiagnosticCheckResult("snapshot", "Air-gapped snapshot freshness", "SKIPPED", "Air-gapped mode disabled", 0));
        results.add(aiCheck());
        results.add(smtpCheck());
        results.addAll(vcsChecks(userId));
        results.add(outboundProbe("osv", "OSV API", "https://api.osv.dev/v1/vulns/GHSA-jfh8-c2jp-5v3q"));
        results.add(outboundProbe("depsdev", "deps.dev API", "https://api.deps.dev/v3/systems/npm/packages/left-pad"));
        results.add(outboundProbe("epss", "FIRST.org EPSS API", "https://api.first.org/data/v1/epss?cve=CVE-1900-0001"));
        results.add(outboundProbe("kev", "CISA KEV catalog",
                "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"));
        return results;
    }

    // ── Health-indicator reuse ───────────────────────────────────────────

    private DiagnosticCheckResult fromHealth(String id, String label, java.util.function.Supplier<Health> probe) {
        long start = System.currentTimeMillis();
        try {
            Health health = probe.get();
            long tookMs = System.currentTimeMillis() - start;
            return new DiagnosticCheckResult(id, label, mapStatus(health.getStatus()), formatDetails(health.getDetails()), tookMs);
        } catch (Exception e) {
            long tookMs = System.currentTimeMillis() - start;
            log.warn("[Diagnostics] {} check threw: {}", id, e.getMessage());
            return new DiagnosticCheckResult(id, label, "DOWN", safeMessage(e), tookMs);
        }
    }

    private static String mapStatus(Status status) {
        if (Status.UP.equals(status)) return "UP";
        if (Status.DOWN.equals(status) || Status.OUT_OF_SERVICE.equals(status)) return "DOWN";
        return "UNKNOWN";
    }

    private static String formatDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        details.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    // ── AI provider (live probe — free model-list call, no tokens burned) ──

    private DiagnosticCheckResult aiCheck() {
        long start = System.currentTimeMillis();
        if (airgapped) {
            return new DiagnosticCheckResult("ai", "AI provider", "SKIPPED", "Air-gapped mode — AI providers are not probed", 0);
        }
        AiSetting active = aiSettingRepository.findByActiveTrue().orElse(null);
        if (active == null) {
            return new DiagnosticCheckResult("ai", "AI provider", "SKIPPED", "No AI provider configured", 0);
        }
        AiConnectionTestResult result = aiAnalysisService.testConnectionDetailed(active);
        long tookMs = System.currentTimeMillis() - start;
        String label = "AI provider (" + active.getProvider() + ")";
        return new DiagnosticCheckResult("ai", label, !result.success() ? "DOWN"
                : result.hint() != null && !result.hint().isBlank() ? "UNKNOWN" : "UP", result.message(), tookMs);
    }

    // ── SMTP ─────────────────────────────────────────────────────────────

    private DiagnosticCheckResult smtpCheck() {
        long start = System.currentTimeMillis();
        try {
            securitySettingService.testStoredMailConnection();
            return new DiagnosticCheckResult("smtp", "SMTP", "UP", "Connection succeeded", System.currentTimeMillis() - start);
        } catch (IllegalStateException e) {
            return new DiagnosticCheckResult("smtp", "SMTP", "SKIPPED", e.getMessage(), 0);
        } catch (Exception e) {
            return new DiagnosticCheckResult("smtp", "SMTP", "DOWN", safeMessage(e), System.currentTimeMillis() - start);
        }
    }

    // ── VCS token validity — the current user's own connections ────────────

    private List<DiagnosticCheckResult> vcsChecks(Long userId) {
        List<UserVcsConnection> connections = userId != null
                ? vcsConnectionRepository.findByUserIdAndActiveTrue(userId)
                : List.of();
        if (connections.isEmpty()) {
            return List.of(new DiagnosticCheckResult("vcs", "VCS tokens", "SKIPPED", "No connected VCS integrations", 0));
        }
        List<DiagnosticCheckResult> checks = new ArrayList<>();
        for (UserVcsConnection conn : connections) {
            long start = System.currentTimeMillis();
            String id = "vcs-" + conn.getProvider().name().toLowerCase();
            String label = "VCS · " + conn.getProvider().name();
            try {
                String token = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                vcsTokenValidator.validate(conn.getProvider(), conn.getServerUrl(), token, conn.getVcsUsername());
                checks.add(new DiagnosticCheckResult(id, label, "UP", "Token is valid", System.currentTimeMillis() - start));
            } catch (IllegalStateException e) {
                checks.add(new DiagnosticCheckResult(id, label, "DOWN", e.getMessage(), System.currentTimeMillis() - start));
            } catch (Exception e) {
                checks.add(new DiagnosticCheckResult(id, label, "DOWN", "Token could not be decrypted or verified", System.currentTimeMillis() - start));
            }
        }
        return checks;
    }

    // ── Outbound threat-intel source reachability ──────────────────────────

    private DiagnosticCheckResult outboundProbe(String id, String label, String url) {
        if (airgapped) {
            return new DiagnosticCheckResult(id, label, "SKIPPED", "Air-gapped mode — no outbound calls are attempted", 0);
        }
        long start = System.currentTimeMillis();
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(PROBE_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(PROBE_READ_TIMEOUT);
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        try {
            // A usable API response is required; transport reachability alone is insufficient.
            client.get().uri(url).retrieve().toBodilessEntity();
            return new DiagnosticCheckResult(id, label, "UP", "Reachable", System.currentTimeMillis() - start);
        } catch (org.springframework.web.client.HttpStatusCodeException httpError) {
            return new DiagnosticCheckResult(id, label, "DOWN", "HTTP " + httpError.getStatusCode().value(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new DiagnosticCheckResult(id, label, "DOWN", safeMessage(e), System.currentTimeMillis() - start);
        }
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }
}
