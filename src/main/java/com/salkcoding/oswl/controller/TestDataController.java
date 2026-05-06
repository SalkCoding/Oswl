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
 *
 * Edge cases covered:
 *  - Multi-severity mix per scan (CRITICAL/HIGH/MEDIUM/LOW/NONE)
 *  - Library with multiple CVEs of different severities
 *  - Library with CVE but no fixVersion (NON_PATCHABLE)
 *  - Library with GHSA id only (no CVE id)
 *  - Library deprecated with a reason
 *  - Library with null fetchedAt (not yet enriched)
 *  - License UNKNOWN (non-standard name), WARN, VIOLATION, OK
 *  - Reviewed=true and ignored=true components
 *  - Component with full DependencyPath trees (direct + 2-level transitive)
 *  - Component with no DependencyPaths (legacy scan)
 *  - ScanResult with FAILED status and error message
 *  - ScanResult with PENDING status (in-progress scan)
 *  - 8 scan history points per project for rich Risk Trend chart
 *  - Version Diff: added / removed / updated / new-threat changes across scans
 *  - Project with GitHub repo vs CLI-only project
 *  - Project with no scans (edge: empty state UI)
 *  - CVE with AI summary populated
 *  - Same library appearing in multiple projects (shared Library row)
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
    private final DependencyPathRepository  dependencyPathRepository;

    @GetMapping("/test")
    @Transactional
    public String insertTestData() {

        // ════════════════════════════════════════════════════════════════
        // 1.  PROJECTS
        // ════════════════════════════════════════════════════════════════

        // projectA — Java/Maven, GitHub, 8 scan history points
        Project projectA = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("backend-api")
                .version("3.2.0")
                .githubRepo("acme-corp/backend-api")
                .latestBranch("main")
                .lastScannedAt(LocalDateTime.now().minusHours(1))
                .build());

        // projectB — TypeScript/NPM, GitHub, multiple branches
        Project projectB = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("frontend-dashboard")
                .version("2.1.0")
                .githubRepo("acme-corp/frontend-dashboard")
                .latestBranch("release/2.1")
                .lastScannedAt(LocalDateTime.now().minusDays(1))
                .build());

        // projectC — Python, CLI-only, moderate history
        Project projectC = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("ml-pipeline")
                .version("0.8.3")
                .lastScannedAt(LocalDateTime.now().minusDays(3))
                .build());

        // projectD — no scans at all (empty-state edge case)
        Project projectD = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("new-service")
                .build());

        // ════════════════════════════════════════════════════════════════
        // 2.  PROJECT VERSIONS
        // ════════════════════════════════════════════════════════════════

        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectA).branch("main").versionNumber(1).importSource(ImportSource.GIT).build());
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectA).branch("develop").versionNumber(2).importSource(ImportSource.GIT).build());
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectA).branch("hotfix/log4shell").versionNumber(3).importSource(ImportSource.GIT).build());
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectB).branch("main").versionNumber(1).importSource(ImportSource.GIT).build());
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectB).branch("release/2.1").versionNumber(2).importSource(ImportSource.GIT).build());
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectC).branch("main").versionNumber(1).importSource(ImportSource.CLI).build());

        // ════════════════════════════════════════════════════════════════
        // 3.  LIBRARIES
        // ════════════════════════════════════════════════════════════════

        // ── MAVEN ecosystem ──────────────────────────────────────────────

        // CRITICAL x2 + deprecated + has fix
        Library log4j = lib("org.apache.logging.log4j:log4j-core", "2.14.1",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, "Deprecated: use log4j 2.17.1+", "2.17.1",
                LocalDateTime.now().minusDays(1));

        // CRITICAL x1 + HIGH x1 — multiple-CVE per library
        Library springWeb = lib("org.springframework:spring-web", "5.3.20",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, null, "6.1.6",
                LocalDateTime.now().minusDays(2));

        // HIGH x1, no fix (NON_PATCHABLE edge case)
        Library springCore = lib("org.springframework:spring-core", "5.3.20",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, null, "6.1.6",
                LocalDateTime.now().minusDays(2));

        // HIGH x1, fixVersion exists (PATCHABLE)
        Library jackson = lib("com.fasterxml.jackson.core:jackson-databind", "2.13.0",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, null, "2.17.0",
                LocalDateTime.now().minusDays(2));

        // MEDIUM x2 + LOW x1
        Library commonsText = lib("org.apache.commons:commons-text", "1.9",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, null, "1.11.0",
                LocalDateTime.now().minusDays(3));

        // CRITICAL, no fix at all (NON_PATCHABLE)
        Library snakeYaml = lib("org.yaml:snakeyaml", "1.30",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                false, null, "2.2",
                LocalDateTime.now().minusDays(3));

        // License WARN + no CVEs (license-only risk)
        Library h2 = lib("com.h2database:h2", "2.1.214",
                "MAVEN", "MPL-2.0", LicenseStatus.WARN,
                false, null, "2.2.224",
                LocalDateTime.now().minusDays(4));

        // License VIOLATION
        Library gplLib = lib("net.sf.jsqlparser:jsqlparser", "4.6",
                "MAVEN", "GPL-2.0-only", LicenseStatus.VIOLATION,
                true, null, null,
                LocalDateTime.now().minusDays(5));

        // License UNKNOWN — non-standard name
        Library internalLib = lib("com.internal:crypto-util", "1.0.0",
                "MAVEN", "Internal-Proprietary-1.0", LicenseStatus.UNKNOWN,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // License UNKNOWN — no name at all (truly unknown)
        Library unknownLic = lib("com.legacy:old-codec", "0.3.2",
                "MAVEN", null, LicenseStatus.UNKNOWN,
                false, null, "1.0.0",
                LocalDateTime.now().minusDays(8));

        // NONE severity (clean, up-to-date, safe)
        Library guava = lib("com.google.guava:guava", "32.1.3-jre",
                "MAVEN", "Apache-2.0", LicenseStatus.OK,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // Not enriched yet (fetchedAt = null)
        Library notFetched = lib("org.example:not-enriched-yet", "1.0.0",
                "MAVEN", null, LicenseStatus.UNKNOWN,
                null, null, null,
                null);

        // CRITICAL — H2 RCE, License WARN (dual risk)
        Library h2Rce = lib("com.h2database:h2", "1.4.200",
                "MAVEN", "MPL-2.0", LicenseStatus.WARN,
                false, null, "2.2.224",
                LocalDateTime.now().minusDays(4));

        // ── NPM ecosystem ────────────────────────────────────────────────

        // HIGH + no fix (NON_PATCHABLE)
        Library lodash = lib("lodash", "4.17.20",
                "NPM", "MIT", LicenseStatus.OK,
                false, null, "4.17.21",
                LocalDateTime.now().minusDays(2));

        // LOW only
        Library axios = lib("axios", "1.2.0",
                "NPM", "MIT", LicenseStatus.OK,
                false, null, "1.6.8",
                LocalDateTime.now().minusDays(2));

        // MEDIUM x1
        Library momentJs = lib("moment", "2.29.3",
                "NPM", "MIT", LicenseStatus.OK,
                false, null, "2.30.1",
                LocalDateTime.now().minusDays(3));

        // License WARN (NPM)
        Library angularCore = lib("@angular/core", "15.0.0",
                "NPM", "MIT", LicenseStatus.OK,
                false, null, "17.3.0",
                LocalDateTime.now().minusDays(2));

        // CRITICAL (prototype pollution)
        Library qs = lib("qs", "6.5.2",
                "NPM", "BSD-3-Clause", LicenseStatus.OK,
                false, null, "6.11.0",
                LocalDateTime.now().minusDays(2));

        // Clean + latest
        Library reactDom = lib("react-dom", "18.2.0",
                "NPM", "MIT", LicenseStatus.OK,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // ── PYPI ecosystem ───────────────────────────────────────────────

        // HIGH
        Library requests = lib("requests", "2.28.0",
                "PYPI", "Apache-2.0", LicenseStatus.OK,
                false, null, "2.31.0",
                LocalDateTime.now().minusDays(5));

        // CRITICAL
        Library pillow = lib("Pillow", "9.0.0",
                "PYPI", "HPND", LicenseStatus.OK,
                false, null, "10.3.0",
                LocalDateTime.now().minusDays(5));

        // MEDIUM + WARN license
        Library urllib3 = lib("urllib3", "1.26.5",
                "PYPI", "MIT", LicenseStatus.OK,
                false, null, "2.2.1",
                LocalDateTime.now().minusDays(5));

        // Clean
        Library numpy = lib("numpy", "1.26.4",
                "PYPI", "BSD-3-Clause", LicenseStatus.OK,
                true, null, null,
                LocalDateTime.now().minusDays(3));

        // CRITICAL — deserialization
        Library pyyaml = lib("PyYAML", "5.3.1",
                "PYPI", "MIT", LicenseStatus.OK,
                false, null, "6.0.1",
                LocalDateTime.now().minusDays(4));

        // ── Persist all ──────────────────────────────────────────────────
        log4j       = libraryRepository.save(log4j);
        springWeb   = libraryRepository.save(springWeb);
        springCore  = libraryRepository.save(springCore);
        jackson     = libraryRepository.save(jackson);
        commonsText = libraryRepository.save(commonsText);
        snakeYaml   = libraryRepository.save(snakeYaml);
        h2          = libraryRepository.save(h2);
        gplLib      = libraryRepository.save(gplLib);
        internalLib = libraryRepository.save(internalLib);
        unknownLic  = libraryRepository.save(unknownLic);
        guava       = libraryRepository.save(guava);
        notFetched  = libraryRepository.save(notFetched);
        h2Rce       = libraryRepository.save(h2Rce);
        lodash      = libraryRepository.save(lodash);
        axios       = libraryRepository.save(axios);
        momentJs    = libraryRepository.save(momentJs);
        angularCore = libraryRepository.save(angularCore);
        qs          = libraryRepository.save(qs);
        reactDom    = libraryRepository.save(reactDom);
        requests    = libraryRepository.save(requests);
        pillow      = libraryRepository.save(pillow);
        urllib3     = libraryRepository.save(urllib3);
        numpy       = libraryRepository.save(numpy);
        pyyaml      = libraryRepository.save(pyyaml);

        // ════════════════════════════════════════════════════════════════
        // 4.  CVEs
        // ════════════════════════════════════════════════════════════════

        // log4j — two CRITICAL CVEs (multi-CVE library)
        cve(log4j, "GHSA-jfh8-c2jp-hdp8", "CVE-2021-44228", RiskLevel.CRITICAL, 10.0,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H",
                "Log4Shell: RCE via JNDI lookup",
                "Apache Log4j2 JNDI features do not protect against attacker-controlled LDAP endpoints. Remote code execution possible without authentication.",
                "2.17.0", "CWE-917",
                "Log4Shell allows unauthenticated RCE via crafted log messages containing JNDI lookups. Update to 2.17.0+ immediately.");

        cve(log4j, "GHSA-7rjr-3q55-vv33", "CVE-2021-45046", RiskLevel.CRITICAL, 9.0,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H",
                "Log4j2 Context Lookup Pattern RCE",
                "The fix for CVE-2021-44228 was incomplete in certain non-default configurations allowing RCE or information leakage.",
                "2.16.0", "CWE-917", null);

        // springWeb — CRITICAL + HIGH combo
        cve(springWeb, "GHSA-45brp-9m3c-2q2c", "CVE-2022-22965", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Spring4Shell: RCE via Data Binding on JDK 9+",
                "A Spring MVC or Spring WebFlux application running on JDK 9+ may be vulnerable to remote code execution (RCE) via data binding.",
                "5.3.18", "CWE-94",
                "Spring4Shell is exploitable on JDK 9+ with Tomcat. The attacker can execute arbitrary code via the classLoader property through data binding.");

        cve(springWeb, "GHSA-2wrp-6fg6-hmc5", "CVE-2022-22950", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Spring Framework DoS via SpEL",
                "Spring Framework contains a vulnerability which causes a Denial of Service (DoS) via SpEL (Spring Expression Language) expression.",
                "5.3.17", "CWE-400", null);

        // springCore — HIGH, no fix (NON_PATCHABLE edge)
        cve(springCore, "GHSA-r4c4-5w5h-v4h9", "CVE-2023-20860", RiskLevel.HIGH, 7.4,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N",
                "Spring Security Authorization Bypass",
                "Spring Security when using MVC pattern matching, could be bypassed via a specially crafted request URI.",
                null, "CWE-285", null);

        // jackson — HIGH, has fix
        cve(jackson, "GHSA-57j2-w4cx-9rqm", "CVE-2022-42003", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Jackson Databind Resource Exhaustion",
                "In FasterXML jackson-databind, resource exhaustion can occur because of a lack of a check in primitive value deserializers.",
                "2.13.4.2", "CWE-502",
                "Sending deeply nested arrays causes unbounded recursion, leading to DoS. Upgrade to 2.14.0+ which limits nesting depth.");

        // commonsText — CRITICAL + MEDIUM + LOW
        cve(commonsText, "GHSA-599f-7c49-w659", "CVE-2022-42889", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Apache Commons Text RCE via StringSubstitutor",
                "Apache Commons Text performs variable interpolation. Properties can be set to execute arbitrary code via 'script', 'dns', or 'url' lookups.",
                "1.10.0", "CWE-94", null);

        cve(commonsText, "GHSA-cwh7-4m5w-xxhc", "CVE-2022-42889", RiskLevel.MEDIUM, 5.3,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N",
                "Commons Text Information Disclosure via lookup",
                "An attacker can use the 'dns' lookup interpolator to exfiltrate internal DNS resolution data.",
                "1.10.0", "CWE-200", null);

        cve(commonsText, null, null, RiskLevel.LOW, 3.1,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:L/I:N/A:N",
                "Commons Text Minor Information Exposure",
                "Minor information exposure possible in some configurations.",
                "1.10.0", null, null);

        // snakeYaml — CRITICAL, no fixVersion
        cve(snakeYaml, "GHSA-3mc7-4q67-w48m", "CVE-2022-1471", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "SnakeYaml Constructor Deserialization RCE",
                "SnakeYaml's Constructor class allows any type to be deserialized unsafely.",
                null, "CWE-502",
                "No official patch available for this version series. Migrate to a safe constructor or replace with Jackson YAML.");

        // h2Rce — CRITICAL + WARN license dual risk
        cve(h2Rce, "GHSA-h376-j262-vhq6", "CVE-2022-23221", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "H2 Console RCE via JDBC URL",
                "H2 Console before 2.1.210 allows remote attackers to execute arbitrary code via a JDBC URL containing the INIT parameter.",
                "2.1.210", "CWE-94", null);

        // lodash — HIGH, no fix
        cve(lodash, "GHSA-jf85-cpcp-j695", "CVE-2021-23337", RiskLevel.HIGH, 7.2,
                "CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H",
                "Lodash Command Injection via template",
                "Lodash versions prior to 4.17.21 are vulnerable to Command Injection via the template function.",
                "4.17.21", null, null);

        // axios — LOW only
        cve(axios, "GHSA-42xw-2xvc-qx8m", "CVE-2023-45857", RiskLevel.LOW, 3.8,
                "CVSS:3.1/AV:N/AC:H/PR:H/UI:R/S:U/C:L/I:L/A:N",
                "Axios CSRF Token Exposure in Cross-Site Requests",
                "Axios can inadvertently expose XSRF-TOKEN cookies to a third party.",
                "1.6.2", null, null);

        // moment — MEDIUM
        cve(momentJs, "GHSA-wc69-rhjr-hc9g", "CVE-2022-24785", RiskLevel.MEDIUM, 5.3,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N",
                "Moment.js Path Traversal in locale file loading",
                "Moment.js vulnerable to path traversal when user-provided locale strings are passed to moment.locale().",
                "2.29.2", "CWE-22", null);

        // qs — CRITICAL (prototype pollution)
        cve(qs, "GHSA-gqgv-6jq5-jjj9", "CVE-2022-24999", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "qs Library Prototype Pollution",
                "qs before 6.10.3 allows attackers to cause a Node process hang because an `__proto__` key is used.",
                "6.10.3", "CWE-1321", null);

        // requests — HIGH (SSRF)
        cve(requests, "GHSA-j8r2-6x86-q33q", "CVE-2023-32681", RiskLevel.HIGH, 6.1,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:C/C:H/I:N/A:N",
                "Requests Proxy-Authorization Header Leak",
                "Requests forwards proxy-authorization header on cross-origin redirects.",
                "2.31.0", "CWE-601", null);

        // pillow — CRITICAL (buffer overflow)
        cve(pillow, "GHSA-56pw-mpj4-fxww", "CVE-2022-22817", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Pillow PIL.ImageMath.eval Arbitrary Code Execution",
                "PIL.ImageMath.eval in Pillow before 9.0.0 allows evaluation of arbitrary expressions, such as ones that use the Python exec method.",
                "9.0.0", "CWE-74", null);

        cve(pillow, "GHSA-4fx9-vc88-q2xc", "CVE-2022-22816", RiskLevel.MEDIUM, 5.1,
                "CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Pillow Path Confusion in tiff_getlist",
                "An issue was discovered in Pillow before 9.0.0 in the path_getbbox function in path.c.",
                "9.0.0", "CWE-125", null);

        // urllib3 — MEDIUM
        cve(urllib3, "GHSA-g4mx-q9vg-27p4", "CVE-2023-45803", RiskLevel.MEDIUM, 4.2,
                "CVSS:3.1/AV:N/AC:H/PR:H/UI:R/S:U/C:H/I:N/A:N",
                "urllib3 Request Body Not Stripped After Redirect",
                "urllib3 previously would not remove the HTTP request body when an HTTP redirect response using status 301, 302, or 303 after PUT or PATCH requests.",
                "2.0.7", "CWE-200", null);

        // pyyaml — CRITICAL
        cve(pyyaml, "GHSA-8q59-q68h-6hv4", "CVE-2020-14343", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "PyYAML Full Load Arbitrary Code Execution",
                "A vulnerability was discovered in the PyYAML library, where it is susceptible to arbitrary code execution when it processes YAML files through the full_load method or with the FullLoader Loader.",
                "5.4", "CWE-20", null);

        // ════════════════════════════════════════════════════════════════
        // 5.  SCAN RESULTS — PROJECT A (8 history points for rich trend)
        // ════════════════════════════════════════════════════════════════

        // Oldest: only jackson issues
        ScanResult scanA1 = scan(projectA, "1.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(90));
        // Growing vuln count
        ScanResult scanA2 = scan(projectA, "1.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(70));
        // log4shell introduced
        ScanResult scanA3 = scan(projectA, "2.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(50));
        // hotfix applied — log4j removed
        ScanResult scanA4 = scan(projectA, "2.0.1-hotfix", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(40));
        // Spring4Shell introduced
        ScanResult scanA5 = scan(projectA, "2.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(25));
        // Major cleanup
        ScanResult scanA6 = scan(projectA, "3.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(14));
        // Minor regression
        ScanResult scanA7 = scan(projectA, "3.1.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(7));
        // Current
        ScanResult scanA8 = scan(projectA, "3.2.0", ScanStatus.COMPLETED, LocalDateTime.now().minusHours(1));
        // Failed scan (error state edge case)
        ScanResult scanAFail = scan(projectA, "3.2.0-rc1", ScanStatus.FAILED, LocalDateTime.now().minusDays(2));
        scanAFail.fail("deps.dev API timeout after 30s — retryable error");
        // Pending scan (in-progress banner edge case)
        ScanResult scanAPending = scan(projectA, "3.3.0-SNAPSHOT", ScanStatus.PENDING, LocalDateTime.now().minusMinutes(5));

        // ════════════════════════════════════════════════════════════════
        // 6.  SCAN RESULTS — PROJECT B & C
        // ════════════════════════════════════════════════════════════════

        ScanResult scanB1 = scan(projectB, "1.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(60));
        ScanResult scanB2 = scan(projectB, "1.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(30));
        ScanResult scanB3 = scan(projectB, "2.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(14));
        ScanResult scanB4 = scan(projectB, "2.1.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(1));

        ScanResult scanC1 = scan(projectC, "0.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(45));
        ScanResult scanC2 = scan(projectC, "0.6.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(30));
        ScanResult scanC3 = scan(projectC, "0.7.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(15));
        ScanResult scanC4 = scan(projectC, "0.8.3", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(3));

        // ════════════════════════════════════════════════════════════════
        // 7.  SCAN COMPONENTS — PROJECT A
        //     Designed so Version Diff shows all change types across scans
        // ════════════════════════════════════════════════════════════════

        // scanA1 — baseline (only jackson + guava)
        addComp(scanA1, jackson,     "Direct (1)", false, false);
        addComp(scanA1, guava,       "Transitive (2)", false, false);
        addComp(scanA1, unknownLic,  "Transitive (1)", false, false);    // UNKNOWN license

        // scanA2 — added snakeYaml, commonsText
        addComp(scanA2, jackson,     "Direct (1)", false, false);
        addComp(scanA2, guava,       "Transitive (2)", false, false);
        addComp(scanA2, snakeYaml,   "Direct (1)", false, false);
        addComp(scanA2, commonsText, "Direct (1)", false, false);
        addComp(scanA2, unknownLic,  "Transitive (1)", false, false);

        // scanA3 — log4j introduced (CRITICAL enters)
        addComp(scanA3, jackson,     "Direct (1)", false, false);
        addComp(scanA3, guava,       "Transitive (2)", false, false);
        addComp(scanA3, log4j,       "Direct (1)", false, false);
        addComp(scanA3, snakeYaml,   "Direct (1)", false, false);
        addComp(scanA3, commonsText, "Direct (1)", false, false);
        addComp(scanA3, gplLib,      "Direct (1)", false, false);        // GPL violation introduced
        addComp(scanA3, unknownLic,  "Transitive (1)", false, false);

        // scanA4 — log4j hotfixed (removed), gpl still present
        addComp(scanA4, jackson,     "Direct (1)", false, false);
        addComp(scanA4, guava,       "Transitive (2)", false, false);
        addComp(scanA4, snakeYaml,   "Direct (1)", false, false);
        addComp(scanA4, commonsText, "Direct (1)", false, false);
        addComp(scanA4, gplLib,      "Direct (1)", false, false);
        addComp(scanA4, unknownLic,  "Transitive (1)", false, false);

        // scanA5 — Spring4Shell introduced, h2Rce added
        addComp(scanA5, jackson,     "Direct (1)", false, false);
        addComp(scanA5, springWeb,   "Direct (1)", false, false);
        addComp(scanA5, springCore,  "Transitive (2)", false, false);
        addComp(scanA5, guava,       "Transitive (3)", false, false);
        addComp(scanA5, snakeYaml,   "Direct (1)", false, false);
        addComp(scanA5, commonsText, "Direct (1)", false, false);
        addComp(scanA5, h2Rce,       "Direct (1)", false, false);
        addComp(scanA5, gplLib,      "Direct (1)", false, false);
        addComp(scanA5, notFetched,  "Transitive (1)", false, false);    // not enriched edge

        // scanA6 — major cleanup: snakeYaml/gpl/h2Rce removed, internalLib added
        addComp(scanA6, jackson,      "Direct (1)", true,  false);       // reviewed=true
        addComp(scanA6, springWeb,    "Direct (1)", false, false);
        addComp(scanA6, springCore,   "Transitive (2)", false, false);
        addComp(scanA6, guava,        "Transitive (3)", false, false);
        addComp(scanA6, commonsText,  "Direct (1)", false, true);        // ignored=true
        addComp(scanA6, internalLib,  "Direct (1)", false, false);
        addComp(scanA6, h2,           "Direct (1)", false, false);       // license WARN

        // scanA7 — minor regression: commonsText re-enabled (ignored lifted)
        addComp(scanA7, jackson,      "Direct (1)", true,  false);
        addComp(scanA7, springWeb,    "Direct (1)", false, false);
        addComp(scanA7, springCore,   "Transitive (2)", false, false);
        addComp(scanA7, guava,        "Transitive (3)", false, false);
        addComp(scanA7, commonsText,  "Direct (1)", false, false);
        addComp(scanA7, internalLib,  "Direct (1)", false, false);
        addComp(scanA7, h2,           "Direct (1)", false, false);
        addComp(scanA7, notFetched,   "Transitive (1)", false, false);

        // scanA8 — current: commonsText removed, springWeb updated in place
        ScanComponent scA8SpringWeb   = addComp(scanA8, springWeb,   "Direct (1)", false, false);
        ScanComponent scA8SpringCore  = addComp(scanA8, springCore,  "Transitive (2)", false, false);
        ScanComponent scA8Jackson     = addComp(scanA8, jackson,     "Direct (1)", true,  false);
        ScanComponent scA8Guava       = addComp(scanA8, guava,       "Transitive (3)", false, false);
        ScanComponent scA8InternalLib = addComp(scanA8, internalLib, "Direct (1)", false, false);
        ScanComponent scA8H2          = addComp(scanA8, h2,          "Direct (1)", false, false);

        // ── DependencyPaths for scanA8 (full path trees) ─────────────────

        // springWeb — direct dep (depth=1)
        savePath(scA8SpringWeb, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20")));

        // springCore — transitive through springWeb
        savePath(scA8SpringCore, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("org.springframework:spring-core", "5.3.20")));

        // jackson — two paths (direct + transitive through spring-web)
        savePath(scA8Jackson, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("com.fasterxml.jackson.core:jackson-databind", "2.13.0")));
        savePath(scA8Jackson, 1,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("com.fasterxml.jackson.core:jackson-databind", "2.13.0")));

        // guava — 3-level transitive
        savePath(scA8Guava, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("org.springframework:spring-core", "5.3.20"),
                        node("com.google.guava:guava", "32.1.3-jre")));

        // h2 — direct
        savePath(scA8H2, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("com.h2database:h2", "2.1.214")));

        // internalLib — no paths (legacy scan edge case, left empty intentionally)

        // ════════════════════════════════════════════════════════════════
        // 8.  SCAN COMPONENTS — PROJECT B (NPM)
        // ════════════════════════════════════════════════════════════════

        // scanB1 — baseline
        addComp(scanB1, lodash,      "Direct (1)", false, false);
        addComp(scanB1, axios,       "Direct (1)", false, false);
        addComp(scanB1, reactDom,    "Direct (1)", false, false);

        // scanB2 — moment + qs added
        addComp(scanB2, lodash,      "Direct (1)", false, false);
        addComp(scanB2, axios,       "Direct (1)", false, false);
        addComp(scanB2, momentJs,    "Direct (1) + Transitive (3)", false, false);
        addComp(scanB2, qs,          "Transitive (5)", false, false);
        addComp(scanB2, reactDom,    "Direct (1)", false, false);
        addComp(scanB2, angularCore, "Direct (1)", false, false);

        // scanB3 — lodash fixed (removed old, re-added clean version not possible since shared,
        //           so we mark it reviewed), qs still present
        addComp(scanB3, lodash,      "Direct (1)", true,  false);       // reviewed
        addComp(scanB3, axios,       "Direct (1)", false, false);
        addComp(scanB3, momentJs,    "Direct (1) + Transitive (3)", false, false);
        addComp(scanB3, qs,          "Transitive (5)", false, false);
        addComp(scanB3, reactDom,    "Direct (1)", false, false);
        addComp(scanB3, angularCore, "Direct (1)", false, false);

        // scanB4 — current: qs ignored, momentJs reviewed
        ScanComponent scB4Lodash  = addComp(scanB4, lodash,      "Direct (1)", true,  false);
        ScanComponent scB4Axios   = addComp(scanB4, axios,       "Direct (1)", false, false);
        ScanComponent scB4Moment  = addComp(scanB4, momentJs,    "Direct (1) + Transitive (3)", true, false);
        ScanComponent scB4Qs      = addComp(scanB4, qs,          "Transitive (5)", false, true); // ignored
        ScanComponent scB4React   = addComp(scanB4, reactDom,    "Direct (1)", false, false);
        ScanComponent scB4Angular = addComp(scanB4, angularCore, "Direct (1)", false, false);

        // DependencyPaths for scanB4
        savePath(scB4Lodash, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("lodash", "4.17.20")));

        savePath(scB4Axios, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("axios", "1.2.0")));

        savePath(scB4Moment, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("moment", "2.29.3")));
        savePath(scB4Moment, 1,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("@angular/core", "15.0.0"),
                        node("moment", "2.29.3")));
        savePath(scB4Moment, 2,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("react-dom", "18.2.0"),
                        node("moment", "2.29.3")));

        savePath(scB4Qs, 0,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("axios", "1.2.0"),
                        node("qs", "6.5.2")));
        savePath(scB4Qs, 1,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("@angular/core", "15.0.0"),
                        node("@angular/http", "15.0.0"),
                        node("qs", "6.5.2")));

        savePath(scB4React, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("react-dom", "18.2.0")));

        savePath(scB4Angular, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("@angular/core", "15.0.0")));

        // ════════════════════════════════════════════════════════════════
        // 9.  SCAN COMPONENTS — PROJECT C (Python / CLI)
        // ════════════════════════════════════════════════════════════════

        addComp(scanC1, requests, "Direct (1)", false, false);
        addComp(scanC1, numpy,    "Direct (1)", false, false);
        addComp(scanC1, pyyaml,   "Direct (1)", false, false);

        addComp(scanC2, requests, "Direct (1)", false, false);
        addComp(scanC2, numpy,    "Direct (1)", false, false);
        addComp(scanC2, pyyaml,   "Direct (1)", false, false);
        addComp(scanC2, urllib3,  "Transitive (1)", false, false);

        addComp(scanC3, requests, "Direct (1)", false, false);
        addComp(scanC3, numpy,    "Direct (1)", false, false);
        addComp(scanC3, pyyaml,   "Direct (1)", false, false);
        addComp(scanC3, urllib3,  "Transitive (1)", false, false);
        addComp(scanC3, pillow,   "Direct (1)", false, false);

        ScanComponent scC4Requests = addComp(scanC4, requests, "Direct (1)", true,  false);
        ScanComponent scC4Numpy    = addComp(scanC4, numpy,    "Direct (1)", false, false);
        ScanComponent scC4Pyyaml   = addComp(scanC4, pyyaml,   "Direct (1)", false, false);
        ScanComponent scC4Urllib3  = addComp(scanC4, urllib3,  "Transitive (1)", false, false);
        ScanComponent scC4Pillow   = addComp(scanC4, pillow,   "Direct (1)", false, true); // ignored

        // DependencyPaths for scanC4
        savePath(scC4Requests, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("requests", "2.28.0")));

        savePath(scC4Urllib3, 0,
                List.of(node("ml-pipeline", "0.8.3"),
                        node("requests", "2.28.0"),
                        node("urllib3", "1.26.5")));

        savePath(scC4Pillow, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("Pillow", "9.0.0")));

        savePath(scC4Pyyaml, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("PyYAML", "5.3.1")));

        return "redirect:/projects";
    }

    // ════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════

    private Library lib(String name, String version, String ecosystem,
                        String licenseName, LicenseStatus licenseStatus,
                        Boolean isLatest, String deprecated, String latestVersion,
                        LocalDateTime fetchedAt) {
        return Library.builder()
                .name(name)
                .version(version)
                .ecosystem(ecosystem)
                .licenseName(licenseName)
                .licenseStatus(licenseStatus)
                .isLatestVersion(isLatest)
                .deprecated(deprecated)
                .latestVersion(latestVersion)
                .fetchedAt(fetchedAt)
                .build();
    }

    private void cve(Library library, String ghsaId, String cveId, RiskLevel severity,
                     double cvssScore, String cvss3Vector, String title, String summary,
                     String fixVersion, String cweId, String aiSummary) {
        Cve c = Cve.builder()
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
                .aiSummary(aiSummary)
                .build();
        library.getCves().add(c);
    }

    private ScanResult scan(Project project, String version, ScanStatus status,
                            LocalDateTime scannedAt) {
        ScanResult s = ScanResult.builder()
                .project(project)
                .version(version)
                .status(status)
                .build();
        s.setScannedAt(scannedAt);
        return scanResultRepository.save(s);
    }

    private ScanComponent addComp(ScanResult scan, Library library,
                                  String depInfo, boolean reviewed, boolean ignored) {
        ScanComponent sc = ScanComponent.builder()
                .scanResult(scan)
                .library(library)
                .dependencyInfo(depInfo)
                .reviewed(reviewed)
                .ignored(ignored)
                .build();
        return scanComponentRepository.save(sc);
    }

    private void savePath(ScanComponent sc, int index, List<DependencyPath.PathNode> nodes) {
        dependencyPathRepository.save(DependencyPath.builder()
                .scanComponent(sc)
                .pathIndex(index)
                .pathNodes(nodes)
                .depth(nodes.size())
                .build());
    }

    private DependencyPath.PathNode node(String name, String version) {
        return DependencyPath.PathNode.builder().name(name).version(version).build();
    }
}
