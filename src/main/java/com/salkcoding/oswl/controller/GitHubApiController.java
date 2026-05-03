package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubImportRequest;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import com.salkcoding.oswl.service.GitHubService;
import com.salkcoding.oswl.service.ProjectService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints used by the Git Integration panel (git-integration.html).
 *
 * Authentication is via a GitHub Personal Access Token (PAT) stored in HttpSession.
 * POST /api/github/connect   — validate PAT and store it in session
 * POST /api/github/disconnect — remove PAT from session
 * All other endpoints require a valid PAT in session (returns 401 otherwise).
 */
@Slf4j
@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GitHubApiController {

    static final String SESSION_GITHUB_TOKEN = "githubAccessToken";

    private final GitHubService gitHubService;
    private final ProjectService projectService;

    // ── Connect (store PAT) ──────────────────────────────────────────────────

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            HttpSession session) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        try {
            String login = gitHubService.getUserLogin(token.trim());
            session.setAttribute(SESSION_GITHUB_TOKEN, token.trim());
            log.info("[GitHub] PAT connected for user '{}'", login);
            return ResponseEntity.ok(Map.of("connected", true, "login", login));
        } catch (GitHubService.GitHubAuthException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token: " + e.getMessage()));
        }
    }

    // ── Disconnect (remove PAT from session) ─────────────────────────────────

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(HttpSession session) {
        session.removeAttribute(SESSION_GITHUB_TOKEN);
        return ResponseEntity.ok(Map.of("disconnected", true));
    }

    // ── Connection status ────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String token = getToken(session);
        if (token == null) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        try {
            String login = gitHubService.getUserLogin(token);
            return ResponseEntity.ok(Map.of("connected", true, "login", login));
        } catch (GitHubService.GitHubAuthException e) {
            session.removeAttribute(SESSION_GITHUB_TOKEN);
            return ResponseEntity.ok(Map.of("connected", false));
        }
    }

    // ── Accounts (user + orgs) ───────────────────────────────────────────────

    @GetMapping("/accounts")
    public ResponseEntity<List<GitHubAccountDto>> accounts(HttpSession session) {
        String token = requireToken(session);
        if (token == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(gitHubService.getAccounts(token));
    }

    // ── Repos ────────────────────────────────────────────────────────────────

    @GetMapping("/repos")
    public ResponseEntity<List<GitHubRepoDto>> repos(
            @RequestParam String account,
            HttpSession session) {
        String token = requireToken(session);
        if (token == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(gitHubService.getRepos(token, account));
    }

    // ── Branches ─────────────────────────────────────────────────────────────

    @GetMapping("/branches")
    public ResponseEntity<List<String>> branches(
            @RequestParam String owner,
            @RequestParam String repo,
            HttpSession session) {
        String token = requireToken(session);
        if (token == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(gitHubService.getBranches(token, owner, repo));
    }

    // ── Import repo → create Project ─────────────────────────────────────────

    @PostMapping("/repos/import")
    public ResponseEntity<Map<String, Object>> importRepo(
            @RequestBody GitHubImportRequest request,
            HttpSession session) {
        String token = requireToken(session);
        if (token == null) return ResponseEntity.status(401).build();

        var project = projectService.createFromGitHub(request.getOwner(), request.getRepo(), request.getBranch());
        log.info("[GitHub Import] Created project '{}' (id={}) from {}/{}@{}",
                project.getName(), project.getId(), request.getOwner(), request.getRepo(), request.getBranch());

        return ResponseEntity.ok(Map.of(
                "projectId", project.getId(),
                "projectName", project.getName()
        ));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private String getToken(HttpSession session) {
        return (String) session.getAttribute(SESSION_GITHUB_TOKEN);
    }

    private String requireToken(HttpSession session) {
        String token = getToken(session);
        if (token == null) {
            log.debug("[GitHubApi] No GitHub token in session");
        }
        return token;
    }
}
