package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectControllerSpec;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController implements ProjectControllerSpec {

    private final ProjectService projectService;

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

    @GetMapping("/git-integration")
    public String gitIntegration() {
        return "projects/git-integration";
    }

    @GetMapping("/api/branches")
    public ResponseEntity<List<String>> getBranches() {
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/api/accounts")
    public ResponseEntity<List<String>> getAccounts() {
        return ResponseEntity.ok(List.of());
    }
}

