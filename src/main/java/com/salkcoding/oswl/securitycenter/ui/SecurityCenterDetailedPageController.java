package com.salkcoding.oswl.securitycenter.ui;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/security-center")
public class SecurityCenterDetailedPageController {

    @GetMapping("/detailed")
    public String detailedPage(Model model) {
        model.addAttribute("riskCards", buildRiskCards());
        model.addAttribute("tableHeaders", buildTableHeaders());
        model.addAttribute("rows", buildRows());
        model.addAttribute("fixTags", buildFixTags());
        model.addAttribute("page", buildPageState());
        return "pages/security-center/detailed/index";
    }

    private List<RiskCardView> buildRiskCards() {
        return List.of(
                new RiskCardView("Risk Graph", "Critical + High", "28", "issues to patch", "is-hot"),
                new RiskCardView("Risk Graph", "Medium + Low", "1,250", "issues to track", "is-cool")
        );
    }

    private List<String> buildTableHeaders() {
        return List.of("Dependency", "Current", "Target", "Severity", "CVE", "Status");
    }

    private List<VulnerabilityRowView> buildRows() {
        return List.of(
                new VulnerabilityRowView(
                        "android-arch-common",
                        "2.2.0-beta01",
                        "2.2.1-alpha01",
                        "Critical",
                        "CVE-2024-11053",
                        "Open"
                ),
                new VulnerabilityRowView(
                        "android-arch-common",
                        "2.2.0-beta01",
                        "2.2.1-alpha01",
                        "High",
                        "CVE-2024-11054",
                        "Open"
                ),
                new VulnerabilityRowView(
                        "commons-io",
                        "2.11.0",
                        "2.17.0",
                        "Medium",
                        "CVE-2024-22011",
                        "Open"
                ),
                new VulnerabilityRowView(
                        "jackson-databind",
                        "2.13.4",
                        "2.17.2",
                        "Critical",
                        "CVE-2024-28395",
                        "Open"
                ),
                new VulnerabilityRowView(
                        "snakeyaml",
                        "1.30",
                        "2.2",
                        "High",
                        "CVE-2024-19874",
                        "Open"
                )
        );
    }

    private List<FixTagView> buildFixTags() {
        return List.of(
                new FixTagView("8 Critical", "critical"),
                new FixTagView("20 High", "high"),
                new FixTagView("150 Medium", "medium"),
                new FixTagView("1.1k Low", "low")
        );
    }

    private DetailedPageStateView buildPageState() {
        return new DetailedPageStateView(
                "fix: upgrade android-arch-common 2.2.0-beta01 -> 2.2.1-alpha01",
                "main",
                "fix/dep-android-arch-common-patch",
                "",
                "chore: bump android-arch-common version",
                "## Security Patch\n"
                        + "Upgrades android-arch-common from v2.2.0-beta01 to v2.2.1-alpha01.\n"
                        + "Resolves: CVE-2024-11053 (CVSS 9.8), CVE-2024-11054 (CVSS 9.5), +18 more.\n"
                        + "License: CC-BY-SA 4.0 - legal review recommended before deploy."
        );
    }

    private record RiskCardView(
            String title,
            String subtitle,
            String value,
            String helper,
            String tone
    ) {
    }

    private record VulnerabilityRowView(
            String dependency,
            String currentVersion,
            String targetVersion,
            String severity,
            String cve,
            String status
    ) {
    }

    private record FixTagView(
            String label,
            String tone
    ) {
    }

    private record DetailedPageStateView(
            String prTitle,
            String targetBranch,
            String newBranchName,
            String reviewer,
            String commitMessage,
            String description
    ) {
    }
}
