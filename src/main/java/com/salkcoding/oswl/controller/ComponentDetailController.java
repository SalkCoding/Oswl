package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.CveDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/projects/{projectId}/components/{componentId}")
public class ComponentDetailController {

    @GetMapping
    public String detail(@PathVariable Long projectId,
                         @PathVariable Long componentId,
                         Model model,
                         HttpServletRequest request) {
        // TODO: replace with ComponentService once available.
        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", "Project " + projectId);
        model.addAttribute("projectVersion", "1.2.5");
        model.addAttribute("componentId", componentId);

        model.addAttribute("componentName", "Android Arch-Common");
        model.addAttribute("componentVersion", "2.2.0-beta01");
        model.addAttribute("reviewed", false);
        model.addAttribute("projectsCount", 7);
        model.addAttribute("patchability", "patchable");
        model.addAttribute("licenseRiskLabel", "A High Risk License");

        model.addAttribute("securityCritical", 8);
        model.addAttribute("securityHigh", 20);
        model.addAttribute("securityMedium", 150);
        model.addAttribute("securityLow", 1100);

        model.addAttribute("recommendedVersion", "2.2.1-alpha01");

        model.addAttribute("cves", List.of(
            CveDto.builder().id("CVE-2024-11053").severity("CRITICAL").cvssScore(9.8)
                .type("RCE").discoveredOn("2024-06-15")
                .affects("Direct dep.").fixVersion("2.2.1-alpha01")
                .aiSummary("An attacker can remotely execute arbitrary code on your server without authentication. Critical data exposure risk.")
                .build(),
            CveDto.builder().id("CVE-2024-11054").severity("CRITICAL").cvssScore(9.5)
                .type("Injection").discoveredOn("2024-06-12")
                .affects("Direct dep.").fixVersion("2.2.1-alpha01")
                .aiSummary("Allows malicious payloads to be injected through unvalidated input. Significant data integrity risk.")
                .build(),
            CveDto.builder().id("CVE-2024-11055").severity("HIGH").cvssScore(8.2)
                .type("XSS").discoveredOn("2024-06-10")
                .affects("Transitive dep.").fixVersion("2.2.1-alpha01")
                .aiSummary("Cross-site scripting vector enables session hijacking under certain configurations.")
                .build()
        ));

        model.addAttribute("licenseName", "Creative Commons Attribution Share Alike 4.0");
        model.addAttribute("licenseRisk", "HIGH");

        if ("true".equals(request.getHeader("HX-Request"))) {
            return "component-detail/fragments/detail-content :: content";
        }

        return "component-detail/index";
    }
}
