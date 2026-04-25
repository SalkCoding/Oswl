package com.salkcoding.oswl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/risk-trend")
public class RiskTrendController {

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        // TODO: replace with RiskTrendService once available.
        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", "Project " + projectId);
        model.addAttribute("projectVersion", "1.2.5");

        model.addAttribute("securityIssues", 155);
        model.addAttribute("securityDelta", 12);
        model.addAttribute("licenseIssues", 155);
        model.addAttribute("licenseDelta", 12);

        return "risk-trend/index";
    }
}
