package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.MailService;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.NotificationSetting;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dispatches scan-completion alerts to configured Slack / Teams webhooks and optional email digest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanNotificationService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final NotificationSettingService notificationSettingService;
    private final ScanResultRepository scanResultRepository;
    private final LibraryRepository libraryRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final OutboundUrlValidator outboundUrlValidator;

    @Async("virtualThreadExecutor")
    public void notifyScanCompleted(Long scanResultId) {
        try {
            ScanResult scan = scanResultRepository.findById(scanResultId).orElse(null);
            if (scan == null) {
                return;
            }
            NotificationSetting settings = notificationSettingService.loadOrCreate();
            ScanSummary summary = summarize(scan.getId());

            boolean critical = summary.criticalCveCount() > 0;
            boolean licenseHit = summary.licenseViolationCount() > 0;
            if (!shouldNotify(settings, critical, licenseHit)) {
                return;
            }

            Project project = scan.getProject();
            String message = buildMessage(project.getName(), scan.getVersion(), summary);

            postWebhook(notificationSettingService.decryptUrl(settings.getSlackWebhookUrl()), message);
            postWebhook(notificationSettingService.decryptUrl(settings.getTeamsWebhookUrl()), message);

            if (settings.isEmailDigestEnabled()) {
                sendEmailDigest(project.getId(), project.getName(), message);
            }
        } catch (Exception e) {
            log.warn("[Notify] scanId={} notification failed: {}", scanResultId, e.getMessage());
        }
    }

    private boolean shouldNotify(NotificationSetting settings, boolean critical, boolean licenseHit) {
        boolean anyChannel = (settings.getSlackWebhookUrl() != null && !settings.getSlackWebhookUrl().isBlank())
                || (settings.getTeamsWebhookUrl() != null && !settings.getTeamsWebhookUrl().isBlank())
                || settings.isEmailDigestEnabled();
        if (!anyChannel) {
            return false;
        }
        if (critical && settings.isNotifyCriticalCve()) {
            return true;
        }
        return licenseHit && settings.isNotifyLicenseViolation();
    }

    private ScanSummary summarize(Long scanId) {
        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scanId);
        int critical = 0;
        int high = 0;
        int licenseViolations = 0;
        for (Library lib : libraries) {
            if (lib.getLicenseStatus() == LicenseStatus.RESTRICTED) {
                licenseViolations++;
            }
            if (lib.getCves() == null) {
                continue;
            }
            for (Cve cve : lib.getCves()) {
                if (cve.getSeverity() == RiskLevel.CRITICAL) {
                    critical++;
                } else if (cve.getSeverity() == RiskLevel.HIGH) {
                    high++;
                }
            }
        }
        return new ScanSummary(critical, high, licenseViolations, libraries.size());
    }

    private String buildMessage(String projectName, String version, ScanSummary summary) {
        return "OsWL scan completed for *" + projectName + "* (version: " + version + ")\n"
                + "- Critical CVEs: " + summary.criticalCveCount() + "\n"
                + "- High CVEs: " + summary.highCveCount() + "\n"
                + "- License violations: " + summary.licenseViolationCount() + "\n"
                + "- Components: " + summary.componentCount();
    }

    private void postWebhook(String url, String text) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            outboundUrlValidator.validateHttpUrl(url);
        } catch (Exception e) {
            log.warn("[Notify] Blocked outbound webhook URL: {}", e.getMessage());
            return;
        }
        try {
            String body = "{\"text\":\"" + escapeJson(text) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("[Notify] Webhook returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("[Notify] Webhook POST failed: {}", e.getMessage());
        }
    }

    private void sendEmailDigest(Long projectId, String projectName, String message) {
        Set<Long> userIds = new HashSet<>(projectMemberRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(m -> m.getUserId())
                .toList());
        for (Long userId : userIds) {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.isEnabled()) {
                continue;
            }
            try {
                mailService.sendPlainNotification(user.getEmail(), user.getDisplayName(),
                        "[OsWL] Scan alert — " + projectName, message.replace("*", ""));
            } catch (Exception e) {
                log.warn("[Notify] Email to {} failed: {}", user.getEmail(), e.getMessage());
            }
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private record ScanSummary(int criticalCveCount, int highCveCount, int licenseViolationCount, int componentCount) {}
}
