package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Development-only controller for seeding comprehensive test data.
 * Only active on the "local" profile — never exposed in production.
 *
 * Access: GET /data/test
 * After seeding, redirects to the main projects page.
 */
@Controller
@RequestMapping("/data")
@RequiredArgsConstructor
@Profile("local")
public class TestDataController {

    private final ProjectRepository         projectRepository;
    private final ProjectVersionRepository  projectVersionRepository;
    private final LibraryRepository         libraryRepository;
    private final ScanResultRepository      scanResultRepository;
    private final ScanComponentRepository   scanComponentRepository;

    @GetMapping("/test")
    @Transactional
    public String insertTestData() {

        // Guard against duplicate seeding
        if (projectRepository.findByGithubRepo("acme-corp/backend-api").isPresent()) {
            return "redirect:/projects";
        }

        // ── 1. Projects ───────────────────────────────────────────────────
        Project projectA = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("backend-api")
                .version("2.4.1")
                .githubRepo("acme-corp/backend-api")
                .latestBranch("main")
                .lastScannedAt(LocalDateTime.now().minusHours(3))
                .build());

        Project projectB = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("frontend-app")
                .version("1.0.0")
                .githubRepo("acme-corp/frontend-app")
                .latestBranch("develop")
                .lastScannedAt(LocalDateTime.now().minusDays(1))
                .build());

        Project projectC = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("data-pipeline")
                .version("0.9.0")
                .lastScannedAt(LocalDateTime.now().minusDays(7))
                .build());

        // ── 2. Project Versions ───────────────────────────────────────────
        ProjectVersion vA1 = projectVersionRepository.save(ProjectVersion.builder()
                .project(projectA)
                .branch("main")
                .versionNumber(1)
                .importSource(ImportSource.GIT)
                .build());

        ProjectVersion vA2 = projectVersionRepository.save(ProjectVersion.builder()
                .project(projectA)
                .branch("develop")
                .versionNumber(2)
                .importSource(ImportSource.GIT)
                .build());

        ProjectVersion vB1 = projectVersionRepository.save(ProjectVersion.builder()
                .project(projectB)
                .branch("develop")
                .versionNumber(1)
                .importSource(ImportSource.GIT)
                .build());

        ProjectVersion vC1 = projectVersionRepository.save(ProjectVersion.builder()
                .project(projectC)
                .branch("main")
                .versionNumber(1)
                .importSource(ImportSource.CLI)
                .build());

        // ── 3. Libraries ──────────────────────────────────────────────────

        // CRITICAL — log4j RCE
        Library log4j = buildLibrary("org.apache.logging.log4j:log4j-core", "2.14.1",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, false, "2.17.1",
                LocalDateTime.now().minusDays(2));

        Library springWeb = buildLibrary("org.springframework:spring-web", "5.3.20",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, false, "6.1.6",
                LocalDateTime.now().minusDays(2));

        // HIGH — jackson-databind deserialization
        Library jackson = buildLibrary("com.fasterxml.jackson.core:jackson-databind", "2.13.0",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, false, "2.17.0",
                LocalDateTime.now().minusDays(2));

        Library guava = buildLibrary("com.google.guava:guava", "31.1-jre",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, true, null,
                LocalDateTime.now().minusDays(5));

        // MEDIUM — commons-text injection
        Library commonsText = buildLibrary("org.apache.commons:commons-text", "1.9",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, false, "1.11.0",
                LocalDateTime.now().minusDays(3));

        Library snakeYaml = buildLibrary("org.yaml:snakeyaml", "1.30",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, false, "2.2",
                LocalDateTime.now().minusDays(3));

        // License WARN
        Library netty = buildLibrary("io.netty:netty-all", "4.1.86.Final",
                "MAVEN", "Apache-2.0", LicenseStatus.OK, true, null,
                LocalDateTime.now().minusDays(1));

        Library h2 = buildLibrary("com.h2database:h2", "2.1.214",
                "MAVEN", "MPL-2.0", LicenseStatus.WARN, false, "2.2.224",
                LocalDateTime.now().minusDays(4));

        // License VIOLATION
        Library gpl = buildLibrary("net.sf.jsqlparser:jsqlparser", "4.6",
                "MAVEN", "GPL-2.0", LicenseStatus.VIOLATION, true, null,
                LocalDateTime.now().minusDays(6));

        // NPM libs for frontend project
        Library lodash = buildLibrary("lodash", "4.17.20",
                "NPM", "MIT", LicenseStatus.OK, false, "4.17.21",
                LocalDateTime.now().minusDays(2));

        Library axios = buildLibrary("axios", "1.2.0",
                "NPM", "MIT", LicenseStatus.OK, false, "1.6.8",
                LocalDateTime.now().minusDays(2));

        Library momentJs = buildLibrary("moment", "2.29.3",
                "NPM", "MIT", LicenseStatus.OK, false, "2.30.1",
                LocalDateTime.now().minusDays(2));

        // Python lib for data-pipeline
        Library requests = buildLibrary("requests", "2.28.0",
                "PYPI", "Apache-2.0", LicenseStatus.OK, false, "2.31.0",
                LocalDateTime.now().minusDays(5));

        Library numpy = buildLibrary("numpy", "1.24.0",
                "PYPI", "BSD-3-Clause", LicenseStatus.OK, false, "1.26.4",
                LocalDateTime.now().minusDays(5));

        // Persist all libraries
        log4j       = libraryRepository.save(log4j);
        springWeb   = libraryRepository.save(springWeb);
        jackson     = libraryRepository.save(jackson);
        guava       = libraryRepository.save(guava);
        commonsText = libraryRepository.save(commonsText);
        snakeYaml   = libraryRepository.save(snakeYaml);
        netty       = libraryRepository.save(netty);
        h2          = libraryRepository.save(h2);
        gpl         = libraryRepository.save(gpl);
        lodash      = libraryRepository.save(lodash);
        axios       = libraryRepository.save(axios);
        momentJs    = libraryRepository.save(momentJs);
        requests    = libraryRepository.save(requests);
        numpy       = libraryRepository.save(numpy);

        // ── 4. CVEs ───────────────────────────────────────────────────────
        addCve(log4j, "GHSA-jfh8-c2jp-hdp8", "CVE-2021-44228",
                RiskLevel.CRITICAL, 10.0, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H",
                "Log4Shell: RCE via JNDI lookup in log messages",
                "Apache Log4j2 JNDI features do not protect against attacker controlled LDAP and other JNDI related endpoints. An attacker who can control log messages can execute arbitrary code.",
                "2.17.0", "CWE-917");

        addCve(log4j, "GHSA-7rjr-3q55-vv33", "CVE-2021-45046",
                RiskLevel.CRITICAL, 9.0, "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H",
                "Log4j2 Context Lookup Pattern RCE",
                "It was found that the fix to address CVE-2021-44228 was incomplete in certain non-default configurations.",
                "2.16.0", "CWE-917");

        addCve(jackson, "GHSA-57j2-w4cx-9rqm", "CVE-2022-42003",
                RiskLevel.HIGH, 7.5, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Jackson Databind Deserialization of Untrusted Data",
                "In FasterXML jackson-databind, resource exhaustion can occur because of a lack of a check in primitive value deserializers.",
                "2.13.4.2", "CWE-502");

        addCve(springWeb, "GHSA-45brp-9m3c-2q2c", "CVE-2022-22965",
                RiskLevel.CRITICAL, 9.8, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Spring4Shell: Spring Framework RCE via Data Binding",
                "A Spring MVC or Spring WebFlux application running on JDK 9+ may be vulnerable to remote code execution (RCE) via data binding.",
                "5.3.18", "CWE-94");

        addCve(commonsText, "GHSA-599f-7c49-w659", "CVE-2022-42889",
                RiskLevel.CRITICAL, 9.8, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Apache Commons Text: Remote Code Execution via StringSubstitutor",
                "Apache Commons Text performs variable interpolation, allowing properties to be dynamically evaluated and expanded. The affected versions allow properties to be set that can execute arbitrary code.",
                "1.10.0", "CWE-94");

        addCve(snakeYaml, "GHSA-3mc7-4q67-w48m", "CVE-2022-1471",
                RiskLevel.CRITICAL, 9.8, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "SnakeYaml Constructor Deserialization RCE",
                "SnakeYaml's Constructor class, which inherits from SafeConstructor, allows any type unsafely to be deserialized by SnakeYaml.",
                "2.0", "CWE-502");

        addCve(h2, "GHSA-h376-j262-vhq6", "CVE-2022-23221",
                RiskLevel.CRITICAL, 9.8, "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "H2 Console Remote Code Execution",
                "H2 Console before 2.1.210 allows remote attackers to execute arbitrary code via a JDBC URL.",
                "2.1.210", "CWE-94");

        addCve(lodash, "GHSA-jf85-cpcp-j695", "CVE-2021-23337",
                RiskLevel.HIGH, 7.2, "CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H",
                "Lodash Command Injection via template",
                "Lodash versions prior to 4.17.21 are vulnerable to Command Injection via the template function.",
                "4.17.21", null);

        // ── 5. Scan Results ───────────────────────────────────────────────

        // projectA/main — 3 scans (history trend)
        ScanResult scanA1Old = createScan(projectA, "2.3.0", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(30));
        ScanResult scanA1Mid = createScan(projectA, "2.3.5", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(15));
        ScanResult scanA1Latest = createScan(projectA, "2.4.1", ScanStatus.COMPLETED,
                LocalDateTime.now().minusHours(3));

        // projectA/develop
        ScanResult scanA2 = createScan(projectA, "2.5.0-SNAPSHOT", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(2));

        // projectB
        ScanResult scanB = createScan(projectB, "1.0.0", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(1));

        // projectC
        ScanResult scanC = createScan(projectC, "0.9.0", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(7));

        // ── 6. Scan Components ────────────────────────────────────────────

        // projectA latest scan — full mix
        addComponent(scanA1Latest, log4j,       "Direct (1)");
        addComponent(scanA1Latest, springWeb,   "Direct (1)");
        addComponent(scanA1Latest, jackson,     "Direct (1) + Transitive (2)");
        addComponent(scanA1Latest, guava,       "Transitive (3)");
        addComponent(scanA1Latest, commonsText, "Direct (1)");
        addComponent(scanA1Latest, snakeYaml,   "Direct (1) + Transitive (1)");
        addComponent(scanA1Latest, netty,       "Transitive (4)");
        addComponent(scanA1Latest, h2,          "Direct (1)");
        addComponent(scanA1Latest, gpl,         "Direct (1)");

        // projectA mid scan — fewer issues
        addComponent(scanA1Mid, log4j,     "Direct (1)");
        addComponent(scanA1Mid, springWeb, "Direct (1)");
        addComponent(scanA1Mid, jackson,   "Direct (1) + Transitive (2)");
        addComponent(scanA1Mid, guava,     "Transitive (3)");
        addComponent(scanA1Mid, netty,     "Transitive (4)");

        // projectA old scan
        addComponent(scanA1Old, jackson, "Direct (1)");
        addComponent(scanA1Old, guava,   "Transitive (3)");
        addComponent(scanA1Old, netty,   "Transitive (4)");

        // projectA develop scan
        addComponent(scanA2, log4j,       "Direct (1)");
        addComponent(scanA2, springWeb,   "Direct (1)");
        addComponent(scanA2, commonsText, "Direct (1)");
        addComponent(scanA2, guava,       "Transitive (3)");

        // projectB (NPM)
        addComponent(scanB, lodash,  "Direct (1)");
        addComponent(scanB, axios,   "Direct (1)");
        addComponent(scanB, momentJs, "Direct (1) + Transitive (2)");

        // projectC (Python)
        addComponent(scanC, requests, "Direct (1)");
        addComponent(scanC, numpy,    "Direct (1)");

        return "redirect:/projects";
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Library buildLibrary(String name, String version, String ecosystem,
                                 String licenseName, LicenseStatus licenseStatus,
                                 boolean isLatest, String latestVersion,
                                 LocalDateTime fetchedAt) {
        return Library.builder()
                .name(name)
                .version(version)
                .ecosystem(ecosystem)
                .licenseName(licenseName)
                .licenseStatus(licenseStatus)
                .isLatestVersion(isLatest)
                .latestVersion(isLatest ? null : latestVersion)
                .fetchedAt(fetchedAt)
                .build();
    }

    private void addCve(Library library, String ghsaId, String cveId, RiskLevel severity,
                        double cvssScore, String cvss3Vector, String title, String summary,
                        String fixVersion, String cweId) {
        Cve cve = Cve.builder()
                .library(library)
                .ghsaId(ghsaId)
                .cveId(cveId)
                .severity(severity)
                .cvssScore(cvssScore)
                .cvss3Vector(cvss3Vector)
                .title(title)
                .summary(summary)
                .fixVersion(fixVersion)
                .cweId(cweId)
                .build();
        library.getCves().add(cve);
    }

    private ScanResult createScan(Project project, String version,
                                  ScanStatus status, LocalDateTime scannedAt) {
        ScanResult scan = ScanResult.builder()
                .project(project)
                .version(version)
                .status(status)
                .build();
        scan.setScannedAt(scannedAt);
        return scanResultRepository.save(scan);
    }

    private void addComponent(ScanResult scan, Library library, String dependencyInfo) {
        scanComponentRepository.save(ScanComponent.builder()
                .scanResult(scan)
                .library(library)
                .dependencyInfo(dependencyInfo)
                .build());
    }
}
