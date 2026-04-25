package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.ProjectSummaryDto;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/projects")
public class ProjectController {

    @GetMapping
    public String index(Model model) {
        // TODO: replace mock data with ProjectService once persistence layer is wired up.
        model.addAttribute("projects", List.of(
            ProjectSummaryDto.builder()
                .id(1L).name("Project 1").version("1.2.5").lastScanned("2026.04.04")
                .criticalRisk(5).highRisk(10).mediumRisk(20).lowRisk(120).build(),
            ProjectSummaryDto.builder()
                .id(2L).name("Project 2").version("0.9.0").lastScanned("2026.04.04")
                .criticalRisk(2).highRisk(8).mediumRisk(15).lowRisk(90).build(),
            ProjectSummaryDto.builder()
                .id(3L).name("Project 3").version("2.0.0").lastScanned("2026.04.04")
                .criticalRisk(0).highRisk(1).mediumRisk(5).lowRisk(30).build()
        ));
        return "projects/index";
    }
}
