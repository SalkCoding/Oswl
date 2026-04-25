package com.salkcoding.oswl.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/license")
public class LicenseController {

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        // TODO: replace with LicenseService once available.
        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", "Project " + projectId);
        model.addAttribute("projectVersion", "1.2.5");

        model.addAttribute("totalLicenses", 27);
        model.addAttribute("criticalRiskCount", 3);
        model.addAttribute("highRiskCount", 6);
        model.addAttribute("mediumRiskCount", 7);
        model.addAttribute("lowRiskCount", 11);

        model.addAttribute("totalObligations", 8);

        return "license/index";
    }
}
