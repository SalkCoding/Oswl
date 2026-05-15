package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.cli.CliAuthRequest;
import com.salkcoding.oswl.dto.cli.CliAuthResponse;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stateless CLI authentication endpoint.
 *
 * POST /api/cli/auth
 *   Body:  { "username": "email@example.com", "password": "..." }
 *   200:   { "userId", "displayName", "projects": [{ "id", "name", "apiKey" }] }
 *   401:   { "error": "Invalid credentials" }
 *
 * No session is created. The response contains project-scoped API keys that the
 * CLI stores in its local config and uses for subsequent `POST /api/scan` calls.
 *
 * Rate-limiting / brute-force protection relies on the surrounding infrastructure
 * (reverse proxy / WAF). The endpoint itself does not implement lock-out to avoid
 * complexity in the local-dev profile; production deployments should add it.
 */
@Slf4j
@RestController
@RequestMapping("/api/cli")
@RequiredArgsConstructor
public class CliAuthController {

    private final UserRepository    userRepository;
    private final ProjectRepository projectRepository;
    private final ApiKeyService     apiKeyService;
    private final PasswordEncoder   passwordEncoder;

    @PostMapping("/auth")
    public ResponseEntity<?> auth(@RequestBody CliAuthRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "username and password are required"));
        }

        User user = userRepository.findByEmail(request.getUsername().trim().toLowerCase())
                .orElse(null);

        if (user == null || !user.isEnabled()
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("[CliAuth] failed login attempt for username={}", request.getUsername());
            return ResponseEntity.status(401)
                    .body(java.util.Map.of("error", "Invalid credentials"));
        }

        // Issue (or reuse) one CLI API key per project for this user.
        List<Project> projects = projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        List<CliAuthResponse.ProjectEntry> entries = projects.stream()
                .map(p -> {
                    ApiKey key = apiKeyService.getOrIssueCliKey(p.getId(), user.getId());
                    return CliAuthResponse.ProjectEntry.builder()
                            .id(p.getId())
                            .name(p.getName())
                            .apiKey(key.getToken())
                            .build();
                })
                .toList();

        log.info("[CliAuth] login userId={} email={} projects={}", user.getId(), user.getEmail(), entries.size());

        return ResponseEntity.ok(CliAuthResponse.builder()
                .userId(user.getId())
                .displayName(user.getDisplayName())
                .projects(entries)
                .build());
    }
}
