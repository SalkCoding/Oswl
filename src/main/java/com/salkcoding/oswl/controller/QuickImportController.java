package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.VcsConnectionService;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.QuickImportRequest;
import com.salkcoding.oswl.service.QuickImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Handles the Quick Import UI and its backing REST API.
 *
 * GET  /projects/quick-import               — Thymeleaf page
 * GET  /api/quick-import/connections        — user's connected VCS accounts
 * GET  /api/quick-import/repos?provider=    — list repos for a VCS provider
 * POST /api/quick-import/start              — start an async import job
 * GET  /api/quick-import/job/{jobId}        — poll job status
 */
@Controller
@RequiredArgsConstructor
public class QuickImportController {

    private final QuickImportService quickImportService;
    private final VcsConnectionService vcsConnectionService;

    // ── Page ─────────────────────────────────────────────────────────────

    @GetMapping("/projects/quick-import")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public String quickImportPage() {
        return "projects/quick-import";
    }

    // ── REST API ──────────────────────────────────────────────────────────

    /**
     * Returns the currently authenticated user's active VCS connections.
     * The frontend loads all three providers in parallel (Promise.all) and
     * renders only those that are connected.
     */
    @GetMapping("/api/quick-import/connections")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<VcsConnectionDto>> connections(
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        return ResponseEntity.ok(vcsConnectionService.findByCurrentUser(principal.getUserId()));
    }

    /**
     * Lists all repositories accessible by the authenticated user for the given VCS provider.
     * Used by the Quick Import repo browser UI.
     *
     * @param provider one of GITHUB, GITLAB, BITBUCKET
     */
    @GetMapping("/api/quick-import/repos")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> listRepos(
            @RequestParam VcsProvider provider,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        try {
            List<QuickImportRepoDto> repos = quickImportService.listRepos(provider, principal.getUserId());
            return ResponseEntity.ok(repos);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Kicks off an asynchronous Quick Import job.
     * Returns the job ID immediately; the UI polls /api/quick-import/job/{jobId}.
     */
    @PostMapping("/api/quick-import/start")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, String>> start(
            @Valid @RequestBody QuickImportRequest request,
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        String jobId = quickImportService.startImport(
                request.getRepoUrl(),
                request.getBranch(),
                principal.getUserId());
        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    /**
     * Polls the status of an async import job.
     * Returns 404 if the jobId is unknown or expired (> 30 min old).
     */
    @GetMapping("/api/quick-import/job/{jobId}")
    @ResponseBody
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuickImportJobStatus> jobStatus(@PathVariable String jobId) {
        QuickImportJobStatus status = quickImportService.getJobStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}
