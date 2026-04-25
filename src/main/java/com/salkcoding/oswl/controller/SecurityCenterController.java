package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.ComponentRowDto;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/projects/{projectId}/security-center")
public class SecurityCenterController {

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        // TODO: replace with SecurityCenterService once available.
        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", "Project " + projectId);
        model.addAttribute("projectVersion", "1.2.5");

        model.addAttribute("securityCritical", 5);
        model.addAttribute("securityHigh", 10);
        model.addAttribute("securityMedium", 20);
        model.addAttribute("securityLow", 120);

        model.addAttribute("licenseCritical", 3);
        model.addAttribute("licenseHigh", 6);
        model.addAttribute("licenseMedium", 7);
        model.addAttribute("licenseLow", 11);

        List<ComponentRowDto> components = List.of(
            ComponentRowDto.builder().id(1L).name("Android Arch-Common").version("2.2.0-beta01")
                .dependencyInfo("Direct (6) + Transitive (1) · Projects (7)")
                .reviewed(true)
                .securityCritical(8).securityHigh(20).securityMedium(150).securityLow(1100)
                .patchability("patchable").licenseStatus("WARN").licenseName("Creative Commons...").build(),
            ComponentRowDto.builder().id(2L).name("A Simple Utility for showing Tooltips").version("0.1.6")
                .dependencyInfo("Direct (3) + Transitive (0) · Projects (2)")
                .reviewed(false)
                .securityCritical(0).securityHigh(2).securityMedium(5).securityLow(0)
                .patchability("non-patchable").licenseStatus("OK").licenseName("Apache-2.0").build(),
            ComponentRowDto.builder().id(3L).name("React Router").version("6.10.0")
                .dependencyInfo("Direct (1) + Transitive (4) · Projects (3)")
                .reviewed(false)
                .securityCritical(2).securityHigh(5).securityMedium(12).securityLow(45)
                .patchability("patchable").licenseStatus("OK").licenseName("MIT").build(),
            ComponentRowDto.builder().id(4L).name("Lodash").version("4.17.21")
                .dependencyInfo("Direct (1) + Transitive (12) · Projects (8)")
                .reviewed(false)
                .securityCritical(1).securityHigh(0).securityMedium(3).securityLow(20)
                .patchability("patchable").licenseStatus("OK").licenseName("MIT").build(),
            ComponentRowDto.builder().id(5L).name("Spring Framework").version("5.3.20")
                .dependencyInfo("Direct (8) + Transitive (24) · Projects (1)")
                .reviewed(true)
                .securityCritical(0).securityHigh(1).securityMedium(8).securityLow(60)
                .patchability("patchable").licenseStatus("OK").licenseName("Apache-2.0").build(),
            ComponentRowDto.builder().id(6L).name("OpenSSL").version("1.1.1k")
                .dependencyInfo("Direct (1) + Transitive (0) · Projects (4)")
                .reviewed(false)
                .securityCritical(3).securityHigh(7).securityMedium(15).securityLow(80)
                .patchability("non-patchable").licenseStatus("VIOLATION").licenseName("OpenSSL License").build(),
            ComponentRowDto.builder().id(7L).name("jQuery").version("3.5.1")
                .dependencyInfo("Direct (1) + Transitive (0) · Projects (5)")
                .reviewed(false)
                .securityCritical(0).securityHigh(2).securityMedium(4).securityLow(15)
                .patchability("patchable").licenseStatus("OK").licenseName("MIT").build(),
            ComponentRowDto.builder().id(8L).name("Bootstrap").version("4.6.0")
                .dependencyInfo("Direct (1) + Transitive (2) · Projects (6)")
                .reviewed(false)
                .securityCritical(0).securityHigh(0).securityMedium(2).securityLow(10)
                .patchability("patchable").licenseStatus("OK").licenseName("MIT").build()
        );
        model.addAttribute("components", components);

        return "security-center/index";
    }
}
