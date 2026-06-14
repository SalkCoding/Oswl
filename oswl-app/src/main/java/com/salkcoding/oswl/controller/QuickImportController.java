package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.VcsConnectionService;
import com.salkcoding.oswl.dto.QuickImportApiError;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobsResponse;
import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.QuickImportRequest;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.exception.QuickImportQueueFullException;
import com.salkcoding.oswl.exception.QuickImportUpstreamException;
import com.salkcoding.oswl.controller.spec.QuickImportControllerSpec;
import com.salkcoding.oswl.service.QuickImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.List;
import java.util.Map;

/**
 * Handles the Quick Import UI and its backing REST API.
 *
 * GET  /projects/quick-import               — Thymeleaf page
 * GET  /api/quick-import/connections        — user's connected VCS accounts
 * GET  /api/quick-import/repos?provider=    — list repos for a VCS provider
 * POST /api/quick-import/start              — start an async import job
 * GET  /api/quick-import/jobs               — list user's jobs
 * GET  /api/quick-import/job/{jobId}/stream — SSE job updates
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class QuickImportController implements QuickImportControllerSpec {

    private final QuickImportService quickImportService;
    private final VcsConnectionService vcsConnectionService;

    // ── Page ─────────────────────────────────────────────────────────────

    @GetMapping("/projects/quick-import")
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public String quickImportPage() {
        return "projects/quick-import";
    }

    // ── REST API ──────────────────────────────────────────────────────────

    @GetMapping("/api/quick-import/connections")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasPermission(null, 'SETTINGS_VCS_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<VcsConnectionDto>> connections(
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        return ResponseEntity.ok(vcsConnectionService.findByCurrentUser(principal.getUserId()));
    }

    @GetMapping("/api/quick-import/repos")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasPermission(null, 'SETTINGS_VCS_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> listRepos(
            @RequestParam VcsProvider provider,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        try {
            List<QuickImportRepoDto> repos = quickImportService.listRepos(provider, principal.getUserId());
            return ResponseEntity.ok(repos);
        } catch (OutboundUrlBlockedException e) {
            return ResponseEntity.badRequest()
                    .body(QuickImportApiError.body(QuickImportMessageKeys.LOAD_REPOS, List.of()));
        } catch (QuickImportUpstreamException e) {
            return ResponseEntity.status(502)
                    .body(QuickImportApiError.body(e.getMessageKey(), e.getMessageArgs()));
        } catch (Exception e) {
            log.warn("[QuickImport] Failed to list repos for provider {} userId={}: {}",
                    provider, principal.getUserId(), e.toString());
            return ResponseEntity.status(502)
                    .body(QuickImportApiError.body(QuickImportMessageKeys.LOAD_REPOS, List.of()));
        }
    }

    @PostMapping("/api/quick-import/start")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> start(
            @Valid @RequestBody QuickImportRequest request,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        try {
            String jobId = quickImportService.startImport(
                    request.getRepoUrl(),
                    request.getBranch(),
                    principal.getUserId());
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (QuickImportQueueFullException e) {
            return ResponseEntity.status(429)
                    .body(QuickImportApiError.body(e.getMessageKey(), e.getMessageArgs()));
        }
    }

    @GetMapping("/api/quick-import/job/{jobId}")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<QuickImportJobStatus> jobStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        QuickImportJobStatus status = quickImportService.getJobStatus(jobId, principal.getUserId());
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/api/quick-import/jobs")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<QuickImportJobsResponse> listJobs(
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        return ResponseEntity.ok(quickImportService.listJobsSnapshot(principal.getUserId()));
    }

    @GetMapping(value = "/api/quick-import/job/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public SseEmitter jobStream(
            @PathVariable String jobId,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        return quickImportService.subscribeJobStream(jobId, principal.getUserId());
    }
}
