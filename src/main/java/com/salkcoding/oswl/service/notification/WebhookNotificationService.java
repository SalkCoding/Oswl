package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.CveAlert;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.WebhookSetting;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import com.salkcoding.oswl.dto.gate.GateResultDto;
import com.salkcoding.oswl.service.WebhookSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends Slack/Teams webhook notifications for security events.
 * Each event type can be toggled independently in the webhook settings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookNotificationService {

    private final WebhookSettingService webhookSettingService;
    private final WebhookMessageBuilder messageBuilder;
    private final WebhookClient webhookClient;
    private final AuditLogService auditLogService;

    // ── New CVE alerts ───────────────────────────────────────────────────

    public void sendNewCveAlert(Project project, List<CveAlert> alerts) {
        WebhookSetting setting = webhookSettingService.getSetting();
        if (!shouldNotify(setting, WebhookEventType.NEW_CVE)) {
            return;
        }
        String url = webhookSettingService.getDecryptedUrl();
        if (url == null || url.isBlank()) {
            log.warn("[Webhook] NEW_CVE notification skipped — URL not configured");
            return;
        }

        String title = "OsWL — " + alerts.size() + " new vulnerabilit"
                + (alerts.size() == 1 ? "y" : "ies") + " detected in " + project.getName();
        List<String> lines = alerts.stream()
                .map(a -> "• " + a.getVulnId()
                        + (a.getCveId() != null && !a.getCveId().equals(a.getVulnId())
                                ? " (" + a.getCveId() + ")" : "")
                        + " — " + a.getLibraryName() + " " + nullSafe(a.getLibraryVersion())
                        + (a.getFixVersion() != null ? " — fix: " + a.getFixVersion() : ""))
                .limit(10)
                .toList();

        String payload = messageBuilder.buildPayload(setting.getProvider(), title, lines,
                "/projects/" + project.getId() + "/security-center");
        sendAndAudit(url, payload, WebhookEventType.NEW_CVE, project,
                alerts.isEmpty() ? null : alerts.getFirst().getId().toString());
    }

    // ── Gate failure ─────────────────────────────────────────────────────

    public void sendGateFailure(Long projectId, GateResultDto result) {
        WebhookSetting setting = webhookSettingService.getSetting();
        if (!shouldNotify(setting, WebhookEventType.GATE_FAILURE)) {
            return;
        }
        String url = webhookSettingService.getDecryptedUrl();
        if (url == null || url.isBlank()) {
            log.warn("[Webhook] GATE_FAILURE notification skipped — URL not configured");
            return;
        }

        String title = "OsWL — Security gate failed for " + result.projectName();
        List<String> lines = List.of(
                "Project: " + result.projectName(),
                "Version: " + nullSafe(result.scanVersion()),
                "Evaluated: " + result.evaluatedCount() + " component(s)",
                "New vulnerabilities: " + result.newVulnerabilityCount(),
                "Violations: " + result.violations().size(),
                "Thresholds — severity ≥ " + result.thresholds().failOnSeverity()
                        + ", KEV=" + result.thresholds().failOnKev()
                        + ", EPSS≥" + result.thresholds().failOnEpss()
                        + ", license=" + result.thresholds().failOnLicenseViolation()
        );

        Project project = projectId != null ? Project.builder().id(projectId).name(result.projectName()).build() : null;
        String payload = messageBuilder.buildPayload(setting.getProvider(), title, lines,
                projectId != null ? "/projects/" + projectId + "/security-center" : null);
        sendAndAudit(url, payload, WebhookEventType.GATE_FAILURE, project,
                result.scanId() != null ? result.scanId().toString() : null);
    }

    // ── Scan failure ─────────────────────────────────────────────────────

    public void sendScanFailure(Long projectId, String projectName, Long scanId, String reason) {
        WebhookSetting setting = webhookSettingService.getSetting();
        if (!shouldNotify(setting, WebhookEventType.SCAN_FAILURE)) {
            return;
        }
        String url = webhookSettingService.getDecryptedUrl();
        if (url == null || url.isBlank()) {
            log.warn("[Webhook] SCAN_FAILURE notification skipped — URL not configured");
            return;
        }

        String title = "OsWL — Scan failed" + (projectName != null ? " for " + projectName : "");
        List<String> lines = List.of(
                "Project: " + nullSafe(projectName),
                "Scan ID: " + scanId,
                "Reason: " + nullSafe(reason)
        );

        Project project = projectId != null ? Project.builder().id(projectId).name(projectName).build() : null;
        String payload = messageBuilder.buildPayload(setting.getProvider(), title, lines,
                projectId != null ? "/projects/" + projectId + "/scan-history" : null);
        sendAndAudit(url, payload, WebhookEventType.SCAN_FAILURE, project,
                scanId != null ? scanId.toString() : null);
    }

    // ── Waiver expiry imminent ───────────────────────────────────────────

    public void sendWaiverExpiryImminent(Project project, List<String> componentSummaries) {
        WebhookSetting setting = webhookSettingService.getSetting();
        if (!shouldNotify(setting, WebhookEventType.WAIVER_EXPIRY)) {
            return;
        }
        String url = webhookSettingService.getDecryptedUrl();
        if (url == null || url.isBlank()) {
            log.warn("[Webhook] WAIVER_EXPIRY notification skipped — URL not configured");
            return;
        }

        String title = "OsWL — Waiver(s) expiring soon in " + project.getName();
        List<String> lines = componentSummaries.stream().limit(10).toList();

        String payload = messageBuilder.buildPayload(setting.getProvider(), title, lines,
                "/projects/" + project.getId() + "/security-center");
        sendAndAudit(url, payload, WebhookEventType.WAIVER_EXPIRY, project, null);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private boolean shouldNotify(WebhookSetting setting, WebhookEventType eventType) {
        if (!setting.isConfigured()) {
            return false;
        }
        return switch (eventType) {
            case NEW_CVE -> setting.isNotifyNewCve();
            case GATE_FAILURE -> setting.isNotifyGateFailure();
            case SCAN_FAILURE -> setting.isNotifyScanFailure();
            case WAIVER_EXPIRY -> setting.isNotifyWaiverExpiry();
        };
    }

    private void sendAndAudit(String url, String payload, WebhookEventType eventType,
                              Project project, String referenceId) {
        Long projectId = project != null ? project.getId() : null;
        String projectName = project != null ? project.getName() : null;

        WebhookClient.DeliveryOutcome outcome = webhookClient.send(
                url, payload, eventType, projectId, projectName, referenceId);

        if (outcome.success()) {
            auditLogService.logAnonymous("[system]", "WEBHOOK.SENT", "PROJECT",
                    projectId != null ? projectId.toString() : "-",
                    projectName != null ? projectName : "-",
                    "event=" + eventType + " ref=" + nullSafe(referenceId));
        } else {
            auditLogService.logAnonymous("[system]", "WEBHOOK.FAILED", "PROJECT",
                    projectId != null ? projectId.toString() : "-",
                    projectName != null ? projectName : "-",
                    "event=" + eventType + " ref=" + nullSafe(referenceId)
                            + " status=" + outcome.lastHttpStatus()
                            + " error=" + nullSafe(outcome.lastError()));
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "-";
    }
}
