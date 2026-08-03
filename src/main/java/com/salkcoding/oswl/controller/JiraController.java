package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.JiraSettingDto;
import com.salkcoding.oswl.service.jira.JiraService;
import com.salkcoding.oswl.service.jira.JiraService.JiraTicketResult;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Jira integration: admin settings + per-component ticket creation.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class JiraController {

    private final JiraService jiraService;

    @GetMapping("/api/settings/jira")
    @PreAuthorize("hasPermission(null, 'SETTINGS_JIRA_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<JiraSettingDto> getSettings() {
        return ResponseEntity.ok(jiraService.getSettings());
    }

    public record JiraSettingsRequest(String baseUrl, String email, String apiToken,
                                      String projectKey, String issueType, boolean enabled) {}

    @PutMapping("/api/settings/jira")
    @PreAuthorize("hasPermission(null, 'SETTINGS_JIRA_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> saveSettings(@RequestBody JiraSettingsRequest req) {
        jiraService.saveSettings(req.baseUrl(), req.email(), req.apiToken(),
                req.projectKey(), req.issueType(), req.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{projectId}/components/{componentId}/jira-ticket")
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> createTicket(@PathVariable Long projectId,
                                                            @PathVariable Long componentId) {
        try {
            JiraTicketResult result = jiraService.createTicket(projectId, componentId);
            return ResponseEntity.ok(Map.of("issueKey", result.issueKey(), "issueUrl", result.issueUrl()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
