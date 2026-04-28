package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.ProjectSummaryDto;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/projects")
public class ProjectController {

    private final Map<Long, ProjectSummaryDto> projects = Collections.synchronizedMap(new LinkedHashMap<>());

    @PostConstruct
    public void initProjects() {
        projects.put(1L, ProjectSummaryDto.builder()
                .id(1L).name("Project 1").version("1.2.5").lastScanned("2026.04.04")
                .securityCritical(5).securityHigh(10).securityMedium(20).securityLow(120)
                .licenseCritical(3).licenseHigh(6).licenseMedium(7).licenseLow(11).build());
        projects.put(2L, ProjectSummaryDto.builder()
                .id(2L).name("Project 2").version("0.9.0").lastScanned("2026.04.03")
                .securityCritical(2).securityHigh(8).securityMedium(15).securityLow(90)
                .licenseCritical(1).licenseHigh(4).licenseMedium(6).licenseLow(9).build());
        projects.put(3L, ProjectSummaryDto.builder()
                .id(3L).name("Project 3").version("2.0.0").lastScanned("2026.04.02")
                .securityCritical(0).securityHigh(1).securityMedium(5).securityLow(30)
                .licenseCritical(0).licenseHigh(2).licenseMedium(3).licenseLow(8).build());
        projects.put(4L, ProjectSummaryDto.builder()
                .id(4L).name("Project 4").version("3.1.2").lastScanned("2026.04.01")
                .securityCritical(8).securityHigh(15).securityMedium(25).securityLow(140)
                .licenseCritical(4).licenseHigh(8).licenseMedium(10).licenseLow(15).build());
        projects.put(5L, ProjectSummaryDto.builder()
                .id(5L).name("Project 5").version("1.0.0").lastScanned("2026.03.31")
                .securityCritical(1).securityHigh(3).securityMedium(8).securityLow(40)
                .licenseCritical(0).licenseHigh(1).licenseMedium(4).licenseLow(7).build());
        projects.put(6L, ProjectSummaryDto.builder()
                .id(6L).name("Project 6").version("4.2.0").lastScanned("2026.03.30")
                .securityCritical(3).securityHigh(7).securityMedium(12).securityLow(80)
                .licenseCritical(2).licenseHigh(5).licenseMedium(6).licenseLow(10).build());
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("projects", new ArrayList<>(projects.values()));
        return "projects/index";
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        return projects.remove(projectId) != null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/cli-integration")
    public String cliIntegration() {
        return "projects/cli-integration";
    }

    @GetMapping("/git-integration")
    public String gitIntegration() {
        return "projects/git-integration";
    }
}
