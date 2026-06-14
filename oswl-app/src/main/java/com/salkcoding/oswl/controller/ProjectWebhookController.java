package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectWebhookControllerSpec;
import com.salkcoding.oswl.dto.ProjectWebhookConfigDto;
import com.salkcoding.oswl.dto.UpdateProjectWebhookRequest;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/webhook")
@PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ProjectWebhookController implements ProjectWebhookControllerSpec {

    private final ProjectWebhookService projectWebhookService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public ResponseEntity<ProjectWebhookConfigDto> get(
            @PathVariable Long projectId) {
        projectAccessService.assertCanViewProject(projectId);
        return ResponseEntity.ok(projectWebhookService.getConfig(projectId));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'PROJECT_UPDATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ProjectWebhookConfigDto> update(
            @PathVariable Long projectId,
            @RequestBody UpdateProjectWebhookRequest request) {
        return ResponseEntity.ok(projectWebhookService.updateConfig(projectId, request));
    }
}
