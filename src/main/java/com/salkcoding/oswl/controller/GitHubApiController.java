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

import java.util.*;

/**
 * REST endpoints used by the Git Integration panel (git-integration.html).
 *
 * Multiple PATs are supported: each GitHub user login maps to its own token,
 * stored as Map<String, String> in the HTTP session.
 *
 * POST   /api/github/connect              — validate PAT and add to session map
 * POST   /api/github/disconnect           — remove all tokens from session
 * DELETE /api/github/accounts/{login}     — remove one account from session
 */
@Slf4j
@RestController
@RequestMapping("/api/github")
@RequiredArgsConstructor
public class GitHubApiController {

    static final String SESSION_GITHUB_TOKENS = "githubTokens";

    private final GitHubService gitHubService;
    private final ProjectService projectService;

    // ── Connect (add PAT to session map) ─────────────────────────────────────

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
            Map<String, String> tokens = getTokensMap(session);
            tokens.put(login, token.trim());
            log.info("[GitHub] PAT connected for user '{}'", login);
            return ResponseEntity.ok(Map.of("connected", true, "login", login));
        } catch (GitHubService.GitHubAuthException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token: " + e.getMessage()));
        }
    }

    // ── Disconnect all ───────────────────────────────────────────────────────

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(HttpSession session) {
        session.removeAttribute(SESSION_GITHUB_TOKENS);
        return ResponseEntity.ok(Map.of("disconnected", true));
    }

    // ── Disconnect single account ─────────────────────────────────────────────

    @DeleteMapping("/accounts/{login}")
    public ResponseEntity<Map<String, Object>> disconnectAccount(
            @PathVariable String login,
            HttpSession session) {
        Map<String, String> tokens = getTokensMap(session);
        tokens.remove(login);
        if (tokens.isEmpty()) {
            session.removeAttribute(SESSION_GITHUB_TOKENS);
        }
        log.info("[GitHub] Disconnected account '{}'", login);
        return ResponseEntity.ok(Map.of("removed", login));
    }

    // ── Connection status ────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        Map<String, String> tokens = getTokensMap(session);
        if (tokens.isEmpty()) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        // Validate first token
        Map.Entry<String, String> first = tokens.entrySet().iterator().next();
        try {
            gitHubService.getUserLogin(first.getValue());
            return ResponseEntity.ok(Map.of("connected", true, "login", first.getKey()));
        } catch (GitHubService.GitHubAuthException e) {
            session.removeAttribute(SESSION_GITHUB_TOKENS);
            return ResponseEntity.ok(Map.of("connected", false));
        }
    }

    // ── Accounts (all connected users + their orgs, de-duplicated) ───────────

    @GetMapping("/accounts")
    public ResponseEntity<List<GitHubAccountDto>> accounts(HttpSession session) {
        Map<String, String> tokens = getTokensMap(session);
        if (tokens.isEmpty()) return ResponseEntity.status(401).build();

        Map<String, GitHubAccountDto> accountMap = new LinkedHashMap<>();
        for (String token : tokens.values()) {
            try {
                for (GitHubAccountDto acc : gitHubService.getAccounts(token)) {
                    accountMap.putIfAbsent(acc.getLogin(), acc);
                }
            } catch (Exception e) {
                log.warn("[GitHub] Could not load accounts for a token: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(new ArrayList<>(accountMap.values()));
    }

    // ── Repos ────────────────────────────────────────────────────────────────

    @GetMapping("/repos")
    public ResponseEntity<List<GitHubRepoDto>> repos(
            @RequestParam String account,
            HttpSession session) {
        String token = getTokenForAccount(session, account);
        if (token == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(gitHubService.getRepos(token, account));
    }

    // ── Branches ─────────────────────────────────────────────────────────────

    @GetMapping("/branches")
    public ResponseEntity<List<String>> branches(
            @RequestParam String owner,
            @RequestParam String repo,
            HttpSession session) {
        String token = getTokenForAccount(session, owner);
        if (token == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(gitHubService.getBranches(token, owner, repo));
    }

    @GetMapping("/branch-updated-at")
    public ResponseEntity<Map<String, String>> branchUpdatedAt(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam String branch,
            HttpSession session) {
        String token = getTokenForAccount(session, owner);
        if (token == null) return ResponseEntity.status(401).build();
        String date = gitHubService.getBranchLastCommitDate(token, owner, repo, branch);
        return ResponseEntity.ok(Map.of("updatedAt", date));
    }

    // ── Import repo → upsert Project ─────────────────────────────────────────

    @PostMapping("/repos/import")
    public ResponseEntity<Map<String, Object>> importRepo(
            @RequestBody GitHubImportRequest request,
            HttpSession session) {
        String token = getTokenForAccount(session, request.getOwner());
        if (token == null) return ResponseEntity.status(401).build();

        var project = projectService.upsertFromGitHub(request.getOwner(), request.getRepo(), request.getBranch());
        log.info("[GitHub Import] Upserted project '{}' (id={}) from {}/{}@{}",
                project.getName(), project.getId(),
                request.getOwner(), request.getRepo(), request.getBranch());

        return ResponseEntity.ok(Map.of(
                "projectId", project.getId(),
                "projectName", project.getName(),
                "projectUuid", project.getProjectUuid()
        ));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> getTokensMap(HttpSession session) {
        Object obj = session.getAttribute(SESSION_GITHUB_TOKENS);
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, String>) m;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        session.setAttribute(SESSION_GITHUB_TOKENS, tokens);
        return tokens;
    }

    /**
     * Finds the best token for a given account/owner.
     * Tries direct match first (account == user login), then falls back to any available token
     * (for org repos where the owner is an org, not a user).
     */
    private String getTokenForAccount(HttpSession session, String account) {
        Map<String, String> tokens = getTokensMap(session);
        if (tokens.isEmpty()) return null;
        String direct = tokens.get(account);
        if (direct != null) return direct;
        return tokens.values().iterator().next(); // fallback: any token
    }
}
