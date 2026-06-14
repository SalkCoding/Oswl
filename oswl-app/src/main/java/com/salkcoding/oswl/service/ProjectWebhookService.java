package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.ProjectWebhookConfigDto;
import com.salkcoding.oswl.dto.UpdateProjectWebhookRequest;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ProjectWebhookService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final ImportWebhookService importWebhookService;

    @Transactional(readOnly = true)
    public ProjectWebhookConfigDto getConfig(Long projectId) {
        projectAccessService.assertCanViewProject(projectId);
        Project project = loadProject(projectId);
        return toDto(project, null);
    }

    @Transactional
    public ProjectWebhookConfigDto updateConfig(Long projectId, UpdateProjectWebhookRequest req) {
        assertCanManageWebhook(projectId);
        Project project = loadProject(projectId);

        String revealedSecret = null;
        if (Boolean.TRUE.equals(req.getRotateSecret())
                || (project.getWebhookSecret() == null && Boolean.TRUE.equals(req.getEnabled()))) {
            revealedSecret = generateSecret();
            project.configureWebhook(project.isWebhookEnabled(), revealedSecret);
        }
        if (req.getEnabled() != null) {
            boolean enabled = req.getEnabled();
            if (enabled && (project.getWebhookSecret() == null || project.getWebhookSecret().isBlank())) {
                revealedSecret = generateSecret();
                project.configureWebhook(true, revealedSecret);
            } else {
                project.configureWebhook(enabled, project.getWebhookSecret());
            }
        }

        projectRepository.save(project);
        return toDto(project, revealedSecret);
    }

    private ProjectWebhookConfigDto toDto(Project project, String revealedSecret) {
        boolean configured = project.getWebhookSecret() != null && !project.getWebhookSecret().isBlank();
        return ProjectWebhookConfigDto.builder()
                .enabled(project.isWebhookEnabled())
                .webhookUrl(importWebhookService.buildWebhookCallbackUrl())
                .secret(revealedSecret)
                .secretConfigured(configured)
                .build();
    }

    private void assertCanManageWebhook(Long projectId) {
        var principal = projectAccessService.currentPrincipal();
        if (principal != null && principal.isSystemAdmin()) {
            return;
        }
        if (principal != null && principal.hasPermission(
                com.salkcoding.oswl.auth.enums.Permission.PROJECT_UPDATE)) {
            projectAccessService.assertCanViewProject(projectId);
            return;
        }
        throw new ForbiddenException("You do not have permission to manage webhooks for this project.");
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    private static String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
