package com.salkcoding.oswl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.List;
import java.util.Arrays;

@Controller
@RequestMapping("/projects")
public class ProjectController {

    @GetMapping
    public String index(Model model) {
        // Mock data for Thymeleaf iteration
        model.addAttribute("projects", Arrays.asList(
            new ProjectDto("Project 1", "2026.04.04", 5, 10, 20, 120),
            new ProjectDto("Project 2", "2026.04.04", 2, 8, 15, 90),
            new ProjectDto("Project 3", "2026.04.04", 0, 1, 5, 30)
        ));
        return "projects/index";
    }

    // Temporary DTO for demonstration purposes
    public static class ProjectDto {
        public String name;
        public String lastScanned;
        public int criticalRisk;
        public int highRisk;
        public int mediumRisk;
        public int lowRisk;

        public ProjectDto(String name, String lastScanned, int criticalRisk, int highRisk, int mediumRisk, int lowRisk) {
            this.name = name;
            this.lastScanned = lastScanned;
            this.criticalRisk = criticalRisk;
            this.highRisk = highRisk;
            this.mediumRisk = mediumRisk;
            this.lowRisk = lowRisk;
        }
    }
}
