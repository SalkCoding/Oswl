package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectContextControllerSpec;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class ProjectContextController implements ProjectContextControllerSpec {

    private final ProjectService projectService;
    private final ProjectAccessService projectAccessService;

    @PatchMapping("/deployment-profile")
    @PreAuthorize("hasPermission(null, 'PROJECT_UPDATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, String>> updateDeploymentProfile(
            @PathVariable Long projectId,
            @RequestBody DeploymentProfileRequest request) {
        projectAccessService.assertCanViewProject(projectId);
        projectService.updateDeploymentProfile(projectId, request.deploymentProfile());
        return ResponseEntity.ok(Map.of("deploymentProfile", request.deploymentProfile().name()));
    }
}
