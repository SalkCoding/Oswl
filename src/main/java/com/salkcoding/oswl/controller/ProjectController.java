package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectControllerSpec;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.service.ProjectService;
import com.salkcoding.oswl.service.ScanStatusEmitterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController implements ProjectControllerSpec {

    private final ProjectService projectService;
    private final ScanStatusEmitterRegistry scanStatusEmitterRegistry;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public String index(Model model) {
        model.addAttribute("projects", projectService.findAll());
        model.addAttribute("trashProjects", projectService.findTrash());
        return "projects/index";
    }

    @GetMapping(value = "/list", produces = "application/json")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<List<ProjectSummaryDto>> listJson() {
        return ResponseEntity.ok(projectService.findAll());
    }

    /** SSE stream: pushes a scan-update event the moment a scan reaches a terminal state. */
    @GetMapping(value = "/scan-status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public SseEmitter scanStatusStream(@RequestParam List<Long> ids) {
        return scanStatusEmitterRegistry.subscribe(ids);
    }

    /** Returns only the project cards grid fragment (used for partial DOM swap on SSE event). */
    @GetMapping("/cards")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public String projectCardsFragment(Model model) {
        model.addAttribute("projects", projectService.findAll());
        return "projects/index :: projectCardsGrid";
    }

    /** Soft-delete — moves to trash. */
    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasPermission(null, 'PROJECT_DELETE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        projectService.delete(projectId);
        return ResponseEntity.noContent().build();
    }

    /** Restore a trashed project. */
    @PostMapping("/{projectId}/restore")
    @PreAuthorize("hasPermission(null, 'PROJECT_RESTORE') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> restoreProject(@PathVariable Long projectId) {
        projectService.restore(projectId);
        return ResponseEntity.noContent().build();
    }

    /** Permanently delete a single trashed project. */
    @DeleteMapping("/{projectId}/permanent")
    @PreAuthorize("hasPermission(null, 'PROJECT_PERMANENT_DELETE') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> permanentDeleteProject(@PathVariable Long projectId) {
        projectService.permanentDelete(projectId);
        return ResponseEntity.noContent().build();
    }

    /** Permanently delete all trashed projects. */
    @DeleteMapping("/trash/all")
    @PreAuthorize("hasPermission(null, 'PROJECT_PERMANENT_DELETE') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> permanentDeleteAll() {
        projectService.permanentDeleteAll();
        return ResponseEntity.noContent().build();
    }

    /** Permanently delete selected trashed projects. */
    @DeleteMapping("/trash/selected")
    @PreAuthorize("hasPermission(null, 'PROJECT_PERMANENT_DELETE') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> permanentDeleteSelected(@RequestBody List<Long> ids) {
        projectService.permanentDeleteSelected(ids);
        return ResponseEntity.noContent().build();
    }

    /** Restore selected trashed projects. */
    @PostMapping("/trash/restore-selected")
    @PreAuthorize("hasPermission(null, 'PROJECT_RESTORE') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> restoreSelected(@RequestBody List<Long> ids) {
        projectService.restoreSelected(ids);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cli-integration")
    public String cliIntegration() {
        return "redirect:/projects";
    }

    /** Create a blank project (no VCS link). Admin or PROJECT_CREATE permission required. */
    @PostMapping
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> createProject(
            @Valid @RequestBody CreateProjectRequest req) {
        Project project = projectService.create(req.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", project.getId(), "name", project.getName()));
    }

    record CreateProjectRequest(
            @NotBlank(message = "Project name is required")
            @Size(max = 200, message = "Project name must not exceed 200 characters")
            String name
    ) {}

    /** Redirect legacy git-integration URL to the new quick-import page. */
    @GetMapping("/git-integration")
    public String gitIntegration() {
        return "redirect:/projects/quick-import";
    }

}

