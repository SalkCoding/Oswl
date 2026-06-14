package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Handles inbound VCS push webhooks and enqueues a Quick Import re-scan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportWebhookService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectRepository projectRepository;
    private final QuickImportService quickImportService;
    private final AuditLogService auditLogService;
    private final UserVcsConnectionRepository vcsConnectionRepository;

    @Value("${oswl.public-base-url:}")
    private String publicBaseUrl;

    public record WebhookResult(boolean accepted, String message, String jobId) {}

    @Transactional(readOnly = true)
    public WebhookResult handlePush(String rawBody, Map<String, String> headers) {
        if (rawBody == null || rawBody.isBlank()) {
            return new WebhookResult(false, "Empty body", null);
        }

        String githubEvent = header(headers, "X-GitHub-Event");
        String gitlabEvent = header(headers, "X-Gitlab-Event");

        try {
            JsonNode root = MAPPER.readTree(rawBody);
            String repoKey;
            String branch;

            if (githubEvent != null) {
                if (!"push".equalsIgnoreCase(githubEvent)) {
                    return new WebhookResult(false, "Ignored event: " + githubEvent, null);
                }
                repoKey = text(root, "repository", "full_name");
                branch = parseRefBranch(text(root, "ref"));
            } else if (gitlabEvent != null) {
                if (!"Push Hook".equalsIgnoreCase(gitlabEvent)) {
                    return new WebhookResult(false, "Ignored event: " + gitlabEvent, null);
                }
                repoKey = text(root, "project", "path_with_namespace");
                if (repoKey == null) {
                    repoKey = text(root, "repository", "path_with_namespace");
                }
                branch = parseRefBranch(text(root, "ref"));
            } else if (root.has("push") && root.get("push").has("changes")) {
                // Bitbucket Cloud push
                repoKey = text(root, "repository", "full_name");
                JsonNode change = root.get("push").get("changes").get(0);
                branch = parseRefBranch(text(change, "new", "name") != null
                        ? "refs/heads/" + text(change, "new", "name")
                        : text(change, "ref", "name"));
            } else {
                return new WebhookResult(false, "Unrecognized webhook provider", null);
            }

            if (repoKey == null || repoKey.isBlank()) {
                return new WebhookResult(false, "Could not resolve repository", null);
            }
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }

            Optional<Project> projectOpt = projectRepository.findByGithubRepoAndDeletedAtIsNull(repoKey);
            if (projectOpt.isEmpty()) {
                log.info("[ImportWebhook] No project for repo={}", repoKey);
                return new WebhookResult(false, "Project not registered for " + repoKey, null);
            }
            Project project = projectOpt.get();

            if (!project.isWebhookEnabled() || project.getWebhookSecret() == null || project.getWebhookSecret().isBlank()) {
                return new WebhookResult(false, "Webhook not enabled for project", null);
            }
            if (!verifySecret(project, rawBody, headers)) {
                auditLogService.logAnonymous("webhook", "IMPORT.WEBHOOK_AUTH_FAILURE", "PROJECT",
                        project.getId().toString(), repoKey, "branch=" + branch);
                throw new ForbiddenException("Invalid webhook signature");
            }

            Long actorUserId = project.getCreatedByUserId();
            if (actorUserId == null) {
                return new WebhookResult(false, "Project has no owner for VCS token lookup", null);
            }

            String repoUrl = resolveRepoUrl(project, actorUserId);
            if (repoUrl == null) {
                return new WebhookResult(false, "Could not resolve clone URL", null);
            }

            String jobId = quickImportService.startImport(repoUrl, branch, actorUserId);
            auditLogService.logAnonymous("webhook", "IMPORT.WEBHOOK_TRIGGERED", "PROJECT",
                    project.getId().toString(), repoKey,
                    "branch=" + branch + " jobId=" + jobId);
            log.info("[ImportWebhook] Triggered re-scan projectId={} repo={} branch={} jobId={}",
                    project.getId(), repoKey, branch, jobId);
            return new WebhookResult(true, "Import queued", jobId);

        } catch (ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[ImportWebhook] Failed to process webhook: {}", e.getMessage());
            return new WebhookResult(false, "Webhook processing failed", null);
        }
    }

    private boolean verifySecret(Project project, String body, Map<String, String> headers) {
        String secret = project.getWebhookSecret();

        String githubSig = header(headers, "X-Hub-Signature-256");
        if (githubSig != null) {
            return verifyHmacSha256(secret, body, githubSig);
        }

        String gitlabToken = header(headers, "X-Gitlab-Token");
        if (gitlabToken != null) {
            return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                    gitlabToken.getBytes(StandardCharsets.UTF_8));
        }

        String oswlSecret = header(headers, "X-OsWL-Webhook-Secret");
        if (oswlSecret != null) {
            return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                    oswlSecret.getBytes(StandardCharsets.UTF_8));
        }

        return false;
    }

    private static boolean verifyHmacSha256(String secret, String body, String headerValue) {
        if (!headerValue.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    headerValue.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveRepoUrl(Project project, Long userId) {
        String repo = project.getGithubRepo();
        VcsProvider provider = project.getVcsProvider();
        if (repo == null || provider == null) {
            return null;
        }
        return switch (provider) {
            case GITHUB -> "https://github.com/" + repo;
            case GITLAB -> {
                String host = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.GITLAB)
                        .map(UserVcsConnection::getServerUrl)
                        .map(ImportWebhookService::hostOnly)
                        .orElse("gitlab.com");
                yield "https://" + host + "/" + repo;
            }
            case BITBUCKET -> {
                String host = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.BITBUCKET)
                        .map(UserVcsConnection::getServerUrl)
                        .map(ImportWebhookService::hostOnly)
                        .orElse("bitbucket.org");
                yield "https://" + host + "/" + repo;
            }
        };
    }

    private static String hostOnly(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return "";
        }
        String s = serverUrl.strip();
        if (s.startsWith("https://")) {
            s = s.substring(8);
        } else if (s.startsWith("http://")) {
            s = s.substring(7);
        }
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    private static String parseRefBranch(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        return ref;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String v = headers.get(name);
        if (v != null) {
            return v;
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String text(JsonNode node, String... path) {
        JsonNode cur = node;
        for (String p : path) {
            if (cur == null) {
                return null;
            }
            cur = cur.get(p);
        }
        return cur != null && !cur.isNull() ? cur.asText() : null;
    }

    public String buildWebhookCallbackUrl() {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.endsWith("/")
                    ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                    : publicBaseUrl;
            return base + "/api/import/webhook";
        }
        return "/api/import/webhook";
    }
}
