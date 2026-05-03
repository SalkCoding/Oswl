package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectControllerSpec;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    public String index(Model model) {
        model.addAttribute("projects", projectService.findAll());
        return "projects/index";
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        projectService.delete(projectId);
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
        return ResponseEntity.ok(List.of(
                "main", "develop", "feature/new-feature",
                "hotfix/bug-fix", "release/v1.0.0", "staging"));
    }

    @GetMapping("/api/accounts")
    public ResponseEntity<List<String>> getAccounts() {
        return ResponseEntity.ok(List.of(
                "OwlCoding", "OWL-Team", "OWL-Analytics", "OWL-Security"));
    }
}
