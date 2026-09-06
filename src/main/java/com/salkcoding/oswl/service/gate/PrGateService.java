package com.salkcoding.oswl.service.gate;
import com.salkcoding.oswl.service.vcs.GitHubService;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.dto.gate.GateRequest;
import com.salkcoding.oswl.dto.gate.GateRequest.GitHubTarget;
import com.salkcoding.oswl.dto.gate.GateResultDto;
import com.salkcoding.oswl.dto.gate.GateResultDto.GitHubResult;
import com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions;
import com.salkcoding.oswl.service.notification.WebhookNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a PR/CI gate: evaluates the scan against policy, then optionally posts the
 * result back to GitHub as a PR comment and/or a Check Run. Keeps {@link GatePolicyService}
 * pure (no VCS dependency) and the controller thin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrGateService {

    private static final String CHECK_RUN_NAME = "OsWL Security Gate";

    private final GatePolicyService gatePolicyService;
    private final GitHubService gitHubService;
    private final AuditLogService auditLogService;
    private final WebhookNotificationService webhookNotificationService;

    public GateResultDto evaluateAndPublish(Long projectId, GateRequest request) {
        GateOptions options = new GateOptions(
                request != null ? request.scanId() : null,
                request != null ? request.failOnSeverity() : null,
                request != null ? request.failOnKev() : null,
                request != null ? request.failOnEpss() : null,
                request != null ? request.failOnLicenseViolation() : null,
                request != null ? request.onlyNew() : null,
                request != null ? request.onlyReachable() : null,
                request != null ? request.failOnSecrets() : null);

        GateResultDto result = gatePolicyService.evaluate(projectId, options);

        auditLogService.logAnonymous("cli-client", "GATE.EVALUATE", "PROJECT",
                projectId.toString(), result.projectName(),
                "scanId=" + result.scanId() + " passed=" + result.passed()
                        + " violations=" + result.violations().size());

        if (!result.passed()) {
            try {
                webhookNotificationService.sendGateFailure(projectId, result);
            } catch (Exception e) {
                log.error("[Gate] Webhook notification failed for projectId={}: {}",
                        projectId, e.getMessage());
            }
        }

        GitHubTarget gh = request != null ? request.github() : null;
        if (gh == null || gh.token() == null || gh.owner() == null || gh.repo() == null) {
            return result;
        }
        return withGitHub(result, gh, projectId);
    }

    private GateResultDto withGitHub(GateResultDto result, GitHubTarget gh, Long projectId) {
        boolean commentPosted = false, checkRunPosted = false;
        String commentUrl = null, checkRunUrl = null, error = null;

        try {
            if (gh.prNumber() != null) {
                commentUrl = gitHubService.postPrComment(
                        gh.token(), gh.owner(), gh.repo(), gh.prNumber(),
                        result.commentMarkdown(), gh.serverUrl());
                commentPosted = true;
            }
            if (gh.headSha() != null && !gh.headSha().isBlank()) {
                checkRunUrl = gitHubService.createCheckRun(
                        gh.token(), gh.owner(), gh.repo(), gh.headSha(),
                        CHECK_RUN_NAME,
                        result.passed() ? "success" : "failure",
                        result.passed() ? "Security gate passed" : "Security gate failed",
                        result.summary(),
                        gh.serverUrl());
                checkRunPosted = true;
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("[Gate] GitHub publish failed for {}/{}: {}", gh.owner(), gh.repo(), e.getMessage());
        }

        auditLogService.logAnonymous("cli-client", "GATE.GITHUB_PUBLISH", "PROJECT",
                projectId.toString(), result.projectName(),
                "repo=" + gh.owner() + "/" + gh.repo()
                        + " comment=" + commentPosted + " checkRun=" + checkRunPosted
                        + (error != null ? " error=" + error : ""));

        GitHubResult ghResult = new GitHubResult(commentPosted, commentUrl, checkRunPosted, checkRunUrl, error);
        return new GateResultDto(
                result.passed(), result.exitCode(), result.projectName(), result.scanId(),
                result.scanVersion(), result.baselineVersion(), result.onlyNew(), result.onlyReachable(),
                result.thresholds(), result.evaluatedCount(), result.newVulnerabilityCount(),
                result.violations(), result.summary(), result.commentMarkdown(), ghResult, result.coverage());
    }
}
