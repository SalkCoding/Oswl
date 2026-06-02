package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.MailService;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.domain.entity.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.*;
import com.salkcoding.oswl.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Development-only controller that seeds comprehensive test data.
 * Enabled only in the "local" profile and never exposed in production.
 *
 * Access: GET /data/test
 * Redirects to the main project page after seeding.
 *
 * Edge-case coverage:
 *  - Mixed severities per scan (CRITICAL/HIGH/MEDIUM/LOW/NONE)
 *  - Libraries with CVEs across multiple severities
 *  - Libraries with CVEs that have no fixVersion (NON_PATCHABLE)
 *  - Libraries with no CVE ID and only a GHSA ID
 *  - Legacy libraries with explanatory reasons
 *  - Libraries without fetchedAt (not yet enriched)
 *  - License states UNKNOWN (non-standard name), CAUTION, RESTRICTED, PERMITTED
 *  - Components with reviewed=true and ignored=true
 *  - Components with full DependencyPath trees (direct + two-hop transitive)
 *  - Components without DependencyPath entries (legacy scan)
 *  - ScanResults in FAILED state with an error message
 *  - ScanResults in PENDING state (scan in progress)
 *  - Eight scan-history points per project for rich Risk Trend charts
 *  - Version Diff changes across scans: added/removed/updated/new-threat
 *  - GitHub-connected projects vs CLI-only projects
 *  - Projects with no scans (empty-state UI edge case)
 *  - CVEs with AI summaries applied
 *  - The same library across multiple projects (shared Library row)
 *  - VCS provider modes: GITHUB (default) and GITLAB (non-default provider)
 *  - Soft-deleted projects (deletedAt != null) — trash/restore UI
 *  - ScanResult lifecycle states: PENDING / SCANNING / ANALYZING / COMPLETED / FAILED
 *  - ScanResult.submittedByUserId set (identify Quick Import scans)
 *  - ScanComponent deferrals (exceptions): legal-review / false-positive / wont-fix / temporary
 *    (including indefinite and expiring deferrals)
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
    private final ApiKeyService             apiKeyService;
    private final MailService               mailService;

    /**
     * Renders the OTP email template with dummy data for local visual preview.
     * GET /data/mail-preview
     */
    @GetMapping("/mail-preview")
    @ResponseBody
    public ResponseEntity<String> mailPreview(
            @org.springframework.web.bind.annotation.RequestParam(name = "name", defaultValue = "test") String name,
            @org.springframework.web.bind.annotation.RequestParam(name = "ai",   defaultValue = "true") boolean ai) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(mailService.buildOtpEmailPreview(name, ai));
    }

    /**
     * Issues a new API key for the first available project. Local profile only.
     * Returns the plain-text token in the response body for local CLI QA convenience.
     *  GET /data/test-api-key
     */
    @GetMapping("/test-api-key")
    @Transactional
    @ResponseBody
    public ResponseEntity<String> issueTestApiKey() {
        return projectRepository.findAll().stream().findFirst()
                .map(p -> {
                    var key = apiKeyService.issue(p.getId(), "cli-qa", null).plainToken();
                    return ResponseEntity.ok(
                            "projectId=" + p.getId() + "\n" +
                            "projectName=" + p.getName() + "\n" +
                            "token=" + key + "\n");
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body("No project. Hit /data/test first.\n"));
    }

    @GetMapping("/test")
    @Transactional
    public String insertTestData() {

        // ================================
        // 0. Cleanup — delete all existing data so this endpoint is idempotent
        // ================================
        // Project cascades ALL — ProjectVersion, ScanResult — ScanComponent — DependencyPath
        // Library cascades ALL — Cve
        List<Project> existingProjects = projectRepository.findAll();
        projectRepository.deleteAll(existingProjects);
        projectRepository.flush();

        List<Library> existingLibraries = libraryRepository.findAll();
        libraryRepository.deleteAll(existingLibraries);
        libraryRepository.flush();

        // ================================
        // 1. Projects
        // ================================

        // projectA — Java/Maven, GitHub, 8 scan-history points
        Project projectA = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("backend-api")
                .version("3.2.0")
                .githubRepo("acme-corp/backend-api")
                .vcsProvider(VcsProvider.GITHUB)
                .latestBranch("main")
                .importedAt(LocalDateTime.now().minusDays(90))
                .createdByUserId(1L)
                .lastScannedAt(LocalDateTime.now().minusHours(1))
                .build());

        // projectB — TypeScript/NPM, GitHub, multiple branches
        Project projectB = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("frontend-dashboard")
                .version("2.1.0")
                .githubRepo("acme-corp/frontend-dashboard")
                .vcsProvider(VcsProvider.GITHUB)
                .latestBranch("release/2.1")
                .importedAt(LocalDateTime.now().minusDays(60))
                .createdByUserId(1L)
                .lastScannedAt(LocalDateTime.now().minusDays(1))
                .build());

        // projectC — Python, CLI-only, medium-depth history
        Project projectC = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("ml-pipeline")
                .version("0.8.3")
                .lastScannedAt(LocalDateTime.now().minusDays(3))
                .build());

        // projectD — no scans (empty-state edge case)
        projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("new-service")
                .build());

        // projectE — GitLab-imported project (non-default VCS provider edge case)
        Project projectE = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("payment-gateway")
                .version("1.4.2")
                .githubRepo("acme-internal/payment-gateway")
                .vcsProvider(VcsProvider.GITLAB)
                .latestBranch("main")
                .importedAt(LocalDateTime.now().minusDays(20))
                .createdByUserId(1L)
                .lastScannedAt(LocalDateTime.now().minusDays(2))
                .build());

        // projectF — soft-delete (trash) edge case — trash/restore UI
        Project projectTrash = projectRepository.save(Project.builder()
                .projectUuid(UUID.randomUUID().toString())
                .name("legacy-monolith")
                .version("0.9.0")
                .githubRepo("acme-corp/legacy-monolith")
                .vcsProvider(VcsProvider.GITHUB)
                .latestBranch("main")
                .importedAt(LocalDateTime.now().minusDays(180))
                .lastScannedAt(LocalDateTime.now().minusDays(60))
                .build());
        projectTrash.softDelete();
        projectRepository.save(projectTrash);

        // ================================
        // 2. Project versions
        // ================================

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

        // GitLab project — single-branch import
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectE).branch("main").versionNumber(1).importSource(ImportSource.GIT).build());

        // Trash project — retain history even while soft-deleted
        projectVersionRepository.save(ProjectVersion.builder()
                .project(projectTrash).branch("main").versionNumber(1).importSource(ImportSource.GIT).build());

        // ================================
        // 3. Libraries (MAVEN × 28 · NPM × 19 · PYPI × 18)
        // ================================

        // ── MAVEN ecosystem ─────────────────────────────────────────────
        // Existing
        Library log4j = lib("org.apache.logging.log4j:log4j-core", "2.16.0",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, "Deprecated: use log4j 2.17.1+", "2.17.1",
                LocalDateTime.now().minusDays(1));

        // CRITICAL x1 + HIGH x1 — multiple CVEs per library
        Library springWeb = lib("org.springframework:spring-web", "5.3.20",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "6.1.6",
                LocalDateTime.now().minusDays(2));

        // HIGH x1, no fix (NON_PATCHABLE edge case)
        Library springCore = lib("org.springframework:spring-core", "5.3.20",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "6.1.6",
                LocalDateTime.now().minusDays(2));

        // HIGH x1, has fixVersion (PATCHABLE)
        Library jackson = lib("com.fasterxml.jackson.core:jackson-databind", "2.13.0",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "2.17.0",
                LocalDateTime.now().minusDays(2));

        // MEDIUM x2 + LOW x1
        Library commonsText = lib("org.apache.commons:commons-text", "1.9",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "1.11.0",
                LocalDateTime.now().minusDays(3));

        // CRITICAL, no fix (NON_PATCHABLE)
        Library snakeYaml = lib("org.yaml:snakeyaml", "1.30",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "2.2",
                LocalDateTime.now().minusDays(3));

        // License CAUTION + no CVE (license-only risk)
        Library h2 = lib("com.h2database:h2", "2.1.214",
                "MAVEN", "MPL-2.0", LicenseStatus.CAUTION,
                false, null, "2.2.224",
                LocalDateTime.now().minusDays(4));

        // License RESTRICTED
        Library gplLib = lib("net.sf.jsqlparser:jsqlparser", "4.6",
                "MAVEN", "GPL-2.0-only", LicenseStatus.RESTRICTED,
                true, null, null,
                LocalDateTime.now().minusDays(5));

        // License UNKNOWN — non-standard name
        Library internalLib = lib("com.internal:crypto-util", "1.0.0",
                "MAVEN", "Internal-Proprietary-1.0", LicenseStatus.UNKNOWN,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // License UNKNOWN — no name (completely unknown)
        Library unknownLic = lib("com.legacy:old-codec", "0.3.2",
                "MAVEN", null, LicenseStatus.UNKNOWN,
                false, null, "1.0.0",
                LocalDateTime.now().minusDays(8));

        // NONE severity (clean, up to date, safe)
        Library guava = lib("com.google.guava:guava", "32.1.3-jre",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // Not yet enriched (fetchedAt = null)
        Library notFetched = lib("org.example:not-enriched-yet", "1.0.0",
                "MAVEN", null, LicenseStatus.UNKNOWN,
                null, null, null,
                null);

        // CRITICAL — H2 RCE, license CAUTION (double risk)
        Library h2Rce = lib("com.h2database:h2", "1.4.200",
                "MAVEN", "MPL-2.0", LicenseStatus.CAUTION,
                false, null, "2.2.224",
                LocalDateTime.now().minusDays(4));

        // ── NPM ecosystem ──────────────────────────────────────────────

        // HIGH + no fix (NON_PATCHABLE)
        Library lodash = lib("lodash", "4.17.20",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "4.17.21",
                LocalDateTime.now().minusDays(2));

        // LOW only
        Library axios = lib("axios", "1.2.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "1.6.8",
                LocalDateTime.now().minusDays(2));

        // MEDIUM x1
        Library momentJs = lib("moment", "2.29.3",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "2.30.1",
                LocalDateTime.now().minusDays(3));

        // License CAUTION (NPM)
        Library angularCore = lib("@angular/core", "15.0.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "17.3.0",
                LocalDateTime.now().minusDays(2));

        // CRITICAL (prototype pollution)
        Library qs = lib("qs", "6.5.2",
                "NPM", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "6.11.0",
                LocalDateTime.now().minusDays(2));

        // Clean + latest version
        Library reactDom = lib("react-dom", "18.2.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                true, null, null,
                LocalDateTime.now().minusDays(1));

        // ── PYPI ecosystem ─────────────────────────────────────────────

        // HIGH
        Library requests = lib("requests", "2.28.0",
                "PYPI", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "2.31.0",
                LocalDateTime.now().minusDays(5));

        // CRITICAL
        Library pillow = lib("Pillow", "9.0.0",
                "PYPI", "HPND", LicenseStatus.PERMITTED,
                false, null, "10.3.0",
                LocalDateTime.now().minusDays(5));

        // MEDIUM + CAUTION license
        Library urllib3 = lib("urllib3", "1.26.5",
                "PYPI", "MIT", LicenseStatus.PERMITTED,
                false, null, "2.2.1",
                LocalDateTime.now().minusDays(5));

        // Clean
        Library numpy = lib("numpy", "1.26.4",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                true, null, null,
                LocalDateTime.now().minusDays(3));

        // CRITICAL — deserialization
        Library pyyaml = lib("PyYAML", "5.3.1",
                "PYPI", "MIT", LicenseStatus.PERMITTED,
                false, null, "6.0.1",
                LocalDateTime.now().minusDays(4));

        // ── NEW MAVEN ───────────────────────────────────────────────────
        Library nettyHandler = lib("io.netty:netty-handler", "4.1.86.Final",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "4.1.101.Final", LocalDateTime.now().minusDays(2));

        Library commonsCollections = lib("commons-collections:commons-collections", "3.2.1",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, "EOL: use commons-collections4", "4.4", LocalDateTime.now().minusDays(5));

        Library commonsIo = lib("commons-io:commons-io", "2.7",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "2.15.1", LocalDateTime.now().minusDays(3));

        Library hibernate = lib("org.hibernate:hibernate-core", "5.6.10.Final",
                "MAVEN", "LGPL-2.1-only", LicenseStatus.CAUTION,
                false, null, "6.4.4.Final", LocalDateTime.now().minusDays(3));

        Library tomcatEmbed = lib("org.apache.tomcat.embed:tomcat-embed-core", "9.0.65",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "10.1.20", LocalDateTime.now().minusDays(4));

        Library springSecCore = lib("org.springframework.security:spring-security-core", "5.7.3",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "6.2.3", LocalDateTime.now().minusDays(3));

        Library okhttp = lib("com.squareup.okhttp3:okhttp", "4.9.3",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "4.12.0", LocalDateTime.now().minusDays(2));

        Library gson = lib("com.google.code.gson:gson", "2.8.9",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "2.10.1", LocalDateTime.now().minusDays(3));

        Library xstream = lib("com.thoughtworks.xstream:xstream", "1.4.18",
                "MAVEN", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "1.4.20", LocalDateTime.now().minusDays(4));

        Library woodstox = lib("com.fasterxml.woodstox:woodstox-core", "6.2.7",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "6.6.2", LocalDateTime.now().minusDays(3));

        Library bouncycastle = lib("org.bouncycastle:bcprov-jdk15on", "1.69",
                "MAVEN", "MIT", LicenseStatus.PERMITTED,
                false, null, "1.78.1", LocalDateTime.now().minusDays(4));

        Library poiOoxml = lib("org.apache.poi:poi-ooxml", "5.2.2",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "5.2.5", LocalDateTime.now().minusDays(3));

        Library groovyAll = lib("org.codehaus.groovy:groovy-all", "3.0.10",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "3.0.21", LocalDateTime.now().minusDays(5));

        Library pdfbox = lib("org.apache.pdfbox:pdfbox", "2.0.27",
                "MAVEN", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "3.0.2", LocalDateTime.now().minusDays(4));

        Library hsqldb = lib("org.hsqldb:hsqldb", "2.6.1",
                "MAVEN", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "2.7.2", LocalDateTime.now().minusDays(5));

        // ── NEW NPM ─────────────────────────────────────────────────────
        Library express = lib("express", "4.18.1",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "4.19.2", LocalDateTime.now().minusDays(2));

        Library bodyParser = lib("body-parser", "1.19.2",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "1.20.2", LocalDateTime.now().minusDays(3));

        Library minimist = lib("minimist", "1.2.5",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "1.2.8", LocalDateTime.now().minusDays(4));

        Library ansiRegex = lib("ansi-regex", "5.0.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "5.0.1", LocalDateTime.now().minusDays(3));

        Library semver = lib("semver", "7.3.7",
                "NPM", "ISC", LicenseStatus.PERMITTED,
                false, null, "7.6.0", LocalDateTime.now().minusDays(2));

        Library toughCookie = lib("tough-cookie", "4.1.2",
                "NPM", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "4.1.4", LocalDateTime.now().minusDays(3));

        Library json5 = lib("json5", "2.2.1",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "2.2.3", LocalDateTime.now().minusDays(3));

        Library babelTraverse = lib("babel-traverse", "6.26.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, "EOL: use @babel/traverse 7.x", "7.24.0", LocalDateTime.now().minusDays(7));

        Library loaderUtils = lib("loader-utils", "2.0.2",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "2.0.4", LocalDateTime.now().minusDays(4));

        Library nodeFetch = lib("node-fetch", "2.6.7",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "3.3.2", LocalDateTime.now().minusDays(3));

        Library passport = lib("passport", "0.6.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "0.7.0", LocalDateTime.now().minusDays(5));

        Library webpack = lib("webpack", "5.74.0",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "5.91.0", LocalDateTime.now().minusDays(2));

        Library socketIoParser = lib("socket.io-parser", "4.2.1",
                "NPM", "MIT", LicenseStatus.PERMITTED,
                false, null, "4.2.4", LocalDateTime.now().minusDays(4));

        // ── NEW PYPI ────────────────────────────────────────────────────
        Library django = lib("Django", "3.2.15",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "4.2.11", LocalDateTime.now().minusDays(4));

        Library flask = lib("Flask", "2.0.3",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "3.0.3", LocalDateTime.now().minusDays(5));

        Library jinja2 = lib("Jinja2", "3.0.3",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "3.1.4", LocalDateTime.now().minusDays(4));

        Library werkzeug = lib("Werkzeug", "2.2.2",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "3.0.3", LocalDateTime.now().minusDays(4));

        Library cryptography = lib("cryptography", "38.0.1",
                "PYPI", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "42.0.5", LocalDateTime.now().minusDays(3));

        Library sqlalchemy = lib("SQLAlchemy", "1.4.40",
                "PYPI", "MIT", LicenseStatus.PERMITTED,
                false, null, "2.0.29", LocalDateTime.now().minusDays(4));

        Library paramiko = lib("paramiko", "2.11.0",
                "PYPI", "LGPL-2.1-only", LicenseStatus.CAUTION,
                false, null, "3.4.0", LocalDateTime.now().minusDays(5));

        Library aiohttp = lib("aiohttp", "3.8.1",
                "PYPI", "Apache-2.0", LicenseStatus.PERMITTED,
                false, null, "3.9.4", LocalDateTime.now().minusDays(4));

        Library certifi = lib("certifi", "2022.9.24",
                "PYPI", "MPL-2.0", LicenseStatus.CAUTION,
                false, null, "2024.2.2", LocalDateTime.now().minusDays(6));

        Library pandas = lib("pandas", "1.5.0",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "2.2.1", LocalDateTime.now().minusDays(4));

        Library scipy = lib("scipy", "1.9.3",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "1.13.0", LocalDateTime.now().minusDays(5));

        Library httpx = lib("httpx", "0.23.0",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "0.27.0", LocalDateTime.now().minusDays(4));

        Library celery = lib("celery", "5.2.7",
                "PYPI", "BSD-3-Clause", LicenseStatus.PERMITTED,
                false, null, "5.3.6", LocalDateTime.now().minusDays(5));

        // Save everything
        // Existing MAVEN
        log4j             = libraryRepository.save(log4j);
        springWeb         = libraryRepository.save(springWeb);
        springCore        = libraryRepository.save(springCore);
        jackson           = libraryRepository.save(jackson);
        commonsText       = libraryRepository.save(commonsText);
        snakeYaml         = libraryRepository.save(snakeYaml);
        h2                = libraryRepository.save(h2);
        gplLib            = libraryRepository.save(gplLib);
        internalLib       = libraryRepository.save(internalLib);
        unknownLic        = libraryRepository.save(unknownLic);
        guava             = libraryRepository.save(guava);
        notFetched        = libraryRepository.save(notFetched);
        h2Rce             = libraryRepository.save(h2Rce);
        // New MAVEN
        nettyHandler      = libraryRepository.save(nettyHandler);
        commonsCollections= libraryRepository.save(commonsCollections);
        commonsIo         = libraryRepository.save(commonsIo);
        hibernate         = libraryRepository.save(hibernate);
        tomcatEmbed       = libraryRepository.save(tomcatEmbed);
        springSecCore     = libraryRepository.save(springSecCore);
        okhttp            = libraryRepository.save(okhttp);
        gson              = libraryRepository.save(gson);
        xstream           = libraryRepository.save(xstream);
        woodstox          = libraryRepository.save(woodstox);
        bouncycastle      = libraryRepository.save(bouncycastle);
        poiOoxml          = libraryRepository.save(poiOoxml);
        groovyAll         = libraryRepository.save(groovyAll);
        pdfbox            = libraryRepository.save(pdfbox);
        hsqldb            = libraryRepository.save(hsqldb);
        // Existing NPM
        lodash            = libraryRepository.save(lodash);
        axios             = libraryRepository.save(axios);
        momentJs          = libraryRepository.save(momentJs);
        angularCore       = libraryRepository.save(angularCore);
        qs                = libraryRepository.save(qs);
        reactDom          = libraryRepository.save(reactDom);
        // New NPM
        express           = libraryRepository.save(express);
        bodyParser        = libraryRepository.save(bodyParser);
        minimist          = libraryRepository.save(minimist);
        ansiRegex         = libraryRepository.save(ansiRegex);
        semver            = libraryRepository.save(semver);
        toughCookie       = libraryRepository.save(toughCookie);
        json5             = libraryRepository.save(json5);
        babelTraverse     = libraryRepository.save(babelTraverse);
        loaderUtils       = libraryRepository.save(loaderUtils);
        nodeFetch         = libraryRepository.save(nodeFetch);
        passport          = libraryRepository.save(passport);
        webpack           = libraryRepository.save(webpack);
        socketIoParser    = libraryRepository.save(socketIoParser);
        // Existing PYPI
        requests          = libraryRepository.save(requests);
        pillow            = libraryRepository.save(pillow);
        urllib3           = libraryRepository.save(urllib3);
        numpy             = libraryRepository.save(numpy);
        pyyaml            = libraryRepository.save(pyyaml);
        // New PYPI
        django            = libraryRepository.save(django);
        flask             = libraryRepository.save(flask);
        jinja2            = libraryRepository.save(jinja2);
        werkzeug          = libraryRepository.save(werkzeug);
        cryptography      = libraryRepository.save(cryptography);
        sqlalchemy        = libraryRepository.save(sqlalchemy);
        paramiko          = libraryRepository.save(paramiko);
        aiohttp           = libraryRepository.save(aiohttp);
        certifi           = libraryRepository.save(certifi);
        pandas            = libraryRepository.save(pandas);
        scipy             = libraryRepository.save(scipy);
        httpx             = libraryRepository.save(httpx);
        celery            = libraryRepository.save(celery);

        // ================================
        // 4.  CVEs
        // ================================

        // log4j — 2 CRITICAL CVEs (multi-CVE library)
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

        // springCore — HIGH, no fix (NON_PATCHABLE edge case)
        cve(springCore, "GHSA-r4c4-5w5h-v4h9", "CVE-2023-20860", RiskLevel.HIGH, 7.4,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N",
                "Spring Security Authorization Bypass",
                "Spring Security when using MVC pattern matching, could be bypassed via a specially crafted request URI.",
                null, "CWE-285", null);

        // jackson — HIGH, fix available
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

        // h2Rce — CRITICAL + CAUTION license double risk
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

        // pillow — CRITICAL + MEDIUM
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

        // ── CVEs for new MAVEN ──────────────────────────────────────────

        cve(nettyHandler, "GHSA-5mcr-gq6c-3hq2", "CVE-2022-41881", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Netty HTTP/2 Denial of Service via HPACK Bomb",
                "Netty's codec-http2 package is susceptible to a DoS attack via a crafted HPACK header block (header compression bomb).",
                "4.1.86.Final", "CWE-400", null);

        cve(commonsCollections, "GHSA-6hgm-866r-3cjv", "CVE-2015-6420", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Apache Commons Collections Deserialization RCE",
                "The InvokerTransformer in Apache Commons Collections allows remote code execution via deserialization of a specially crafted object graph.",
                "3.2.2", "CWE-502",
                "The classic Java deserialization gadget chain. Any endpoint that deserializes untrusted Java data with commons-collections on classpath is exploitable.");

        cve(commonsIo, "GHSA-gwrp-pvrq-jmwv", "CVE-2021-29425", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
                "Apache Commons IO Path Traversal",
                "In Apache Commons IO before 2.7, when invoking the method FileNameUtils.normalize with an improper input string, a path traversal defect can be present.",
                "2.7", "CWE-22", null);

        cve(hibernate, "GHSA-wqr6-8m8q-87hp", "CVE-2019-14900", RiskLevel.MEDIUM, 6.5,
                "CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N",
                "Hibernate ORM SQL Injection via HQL",
                "A flaw was found in Hibernate ORM. A specially crafted HQL query can lead to SQL injection if the argument is not properly sanitized.",
                "5.4.18.Final", "CWE-89", null);

        cve(tomcatEmbed, "GHSA-65mq-73vg-f8ww", "CVE-2022-34305", RiskLevel.HIGH, 6.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N",
                "Apache Tomcat XSS in Examples Web Application",
                "The FormAuthenticator HttpServletRequest.authenticate method in Apache Tomcat is vulnerable to Cross-Site Scripting via XSS in the examples web application.",
                "9.0.65", "CWE-79", null);

        cve(springSecCore, "GHSA-hh32-7344-cg2f", "CVE-2022-31692", RiskLevel.HIGH, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Spring Security Authorization Bypass via Forward or Include",
                "Spring Security allows authentication success for endpoints that are secured with a filter chain that uses a different dispatch type.",
                "5.7.5", "CWE-863", null);

        cve(okhttp, "GHSA-wsrf-cjqc-pjv9", "CVE-2023-3635", RiskLevel.MEDIUM, 5.9,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "OkHttp GzipSource Buffer Overflow DoS",
                "GzipSource in okhttp3 doesn't handle exceptions thrown from DeflaterOutputStream.finish() which can cause a DoS.",
                "4.12.0", "CWE-400", null);

        cve(gson, "GHSA-4jrv-ppp4-jm57", "CVE-2022-25647", RiskLevel.LOW, 3.7,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:L",
                "Gson Deserialization of Untrusted Data",
                "The package com.google.code.gson:gson before 2.8.9 is vulnerable to Deserialization of Untrusted Data.",
                "2.8.9", "CWE-502", null);

        cve(xstream, "GHSA-j9h8-phrw-h4fh", "CVE-2022-41966", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "XStream Remote Code Execution via Unsafe Deserialization",
                "XStream serializes Java objects to XML and back again. Versions prior to 1.4.20 may allow a remote attacker to run arbitrary shell commands by manipulating the processed input stream.",
                "1.4.20", "CWE-94",
                "XStream's type whitelisting is insufficient. An attacker can craft an XML payload that causes the JVM to execute arbitrary OS commands during deserialization.");

        cve(woodstox, "GHSA-3f7h-mf4q-vrm4", "CVE-2022-40152", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Woodstox XML Processing DoS via Stack Overflow",
                "Those using Woodstox to parse XML data may be vulnerable to Denial of Service attacks (DoS) if DTD support is enabled. If the parser is running on user supplied input, an attacker may supply content that causes the parser to crash by stackoverflow.",
                "6.4.0", "CWE-400", null);

        cve(bouncycastle, "GHSA-hr8g-6v94-x4m9", "CVE-2023-33201", RiskLevel.MEDIUM, 5.3,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N",
                "Bouncy Castle LDAP Injection via X.500 Name",
                "Bouncy Castle For Java before 1.74 is vulnerable to LDAP injection via a certificate with a malformed policy.",
                "1.74", "CWE-90", null);

        cve(poiOoxml, "GHSA-9rf7-g4jg-4h3p", "CVE-2022-26336", RiskLevel.MEDIUM, 5.5,
                "CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:H",
                "Apache POI OOXML Denial of Service via TNEF File",
                "A shortcoming in the HMEF package of poi-scratchpad (Apache POI) allows an attacker to cause an infinite loop on parsing a crafted TNEF file.",
                "5.2.1", "CWE-835", null);

        cve(groovyAll, "GHSA-rcjj-h6gh-jf3r", "CVE-2022-40146", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "Groovy Script Engine Remote Code Execution",
                "GroovyShell can be used by attackers to run arbitrary code on the server when untrusted Groovy scripts are evaluated.",
                "3.0.13", "CWE-94",
                "Unsandboxed Groovy evaluation is equivalent to Runtime.exec(). Any user-controlled input reaching GroovyShell.evaluate() results in full RCE.");

        cve(pdfbox, "GHSA-r36v-f8cg-9wp3", "CVE-2021-31811", RiskLevel.MEDIUM, 5.5,
                "CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:H",
                "Apache PDFBox Out of Memory Error via Crafted PDF",
                "A carefully crafted PDF file can trigger an OutOfMemory-Exception while loading a tiny file if the PDF file has a corrupt object stream header.",
                "2.0.24", "CWE-400", null);

        cve(hsqldb, "GHSA-77xx-rxvh-q682", "CVE-2022-41853", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "HSQLDB JDBC Remote Code Execution",
                "Those using HyperSQL DataBase (HSQLDB) for running a remote HSQLDB server can be exposed to remote code execution when connecting to a malicious server via a URL that contains a crafted Java class path.",
                "2.7.1", "CWE-94", null);

        // ── CVEs for new NPM ────────────────────────────────────────────

        cve(express, "GHSA-rv95-896h-c2vc", "CVE-2024-29041", RiskLevel.MEDIUM, 6.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N",
                "Express.js Open Redirect",
                "Express.js before 4.19.2 is vulnerable to an open redirect vulnerability when the response.location() URL argument has a leading forward slash.",
                "4.19.2", "CWE-601", null);

        cve(bodyParser, "GHSA-qwcr-r2fm-qrc7", "CVE-2024-45590", RiskLevel.MEDIUM, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "body-parser Denial of Service via URL Encoding",
                "body-parser is vulnerable to denial of service when URL encoding is enabled and a specific payload causes the module to hang.",
                "1.20.3", "CWE-405", null);

        cve(minimist, "GHSA-xvch-5gv4-984h", "CVE-2021-44906", RiskLevel.HIGH, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "minimist Prototype Pollution",
                "Minimist before 1.2.6 is vulnerable to prototype pollution via the '--__proto__' option.",
                "1.2.6", "CWE-1321", null);

        cve(ansiRegex, "GHSA-93q8-gq69-wqmw", "CVE-2021-3807", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "ansi-regex Regular Expression DoS",
                "ansi-regex before 6.0.1 is vulnerable to Regular Expression Denial of Service (ReDoS) via the ANSI escape code regex.",
                "5.0.1", "CWE-1333", null);

        cve(semver, "GHSA-c2qf-rxjj-qqgw", "CVE-2022-25883", RiskLevel.MEDIUM, 5.3,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L",
                "semver Regular Expression Denial of Service",
                "Versions of the package semver before 7.5.2 are vulnerable to Regular Expression Denial of Service (ReDoS) via the function new Range.",
                "7.5.2", "CWE-1333", null);

        cve(toughCookie, "GHSA-72xf-g2v4-qvf3", "CVE-2023-26136", RiskLevel.HIGH, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "tough-cookie Prototype Pollution",
                "Versions of the package tough-cookie before 4.1.3 are vulnerable to Prototype Pollution due to improper handling of Cookies when using CookieJar in rejectPublicSuffixes=false mode.",
                "4.1.3", "CWE-1321", null);

        cve(json5, "GHSA-9c47-m6qq-7p4h", "CVE-2022-46175", RiskLevel.HIGH, 8.8,
                "CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H",
                "JSON5 Prototype Pollution",
                "JSON5 before 1.0.2 and 2.2.2 is vulnerable to prototype pollution, which can be exploited to set arbitrary and unexpected keys on the object returned from JSON5.parse.",
                "2.2.2", "CWE-1321", null);

        cve(babelTraverse, "GHSA-67hx-6x53-jw92", "CVE-2023-45133", RiskLevel.CRITICAL, 9.3,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:N",
                "babel-traverse Remote Code Execution via Code Generation",
                "babel-traverse uses a polyfill for evaluating strings which is vulnerable to code injection. Arbitrary code execution is possible when compiling code that was specifically crafted to exploit this vulnerability.",
                "7.23.2", "CWE-94",
                "Any CI/CD pipeline that compiles untrusted code with an affected version is exploitable. Upgrade to @babel/traverse 7.23.2 immediately.");

        cve(loaderUtils, "GHSA-76p3-8jx3-jpfq", "CVE-2022-37601", RiskLevel.CRITICAL, 9.8,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                "loader-utils Prototype Pollution via parseQuery",
                "Prototype pollution in parseQuery in webpack/loader-utils. An attacker can inject properties via specially crafted query string parameters.",
                "2.0.3", "CWE-1321",
                "Affects any webpack-based build that processes user-controlled query strings. Upgrade to loader-utils 2.0.4 or 3.2.1.");

        cve(nodeFetch, "GHSA-r683-j2x4-v87g", "CVE-2022-0235", RiskLevel.MEDIUM, 6.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N",
                "node-fetch SSRF and Open Redirect via Follow Redirect",
                "node-fetch forwards sensitive headers (like Authorization) on redirect to a different host, enabling SSRF-style attacks.",
                "2.6.7", "CWE-601", null);

        cve(passport, "GHSA-v923-w3x8-wh69", "CVE-2022-25896", RiskLevel.MEDIUM, 5.9,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N",
                "Passport.js Session Fixation",
                "Passport.js before 0.6.0 allows attackers to bypass authentication by submitting a crafted session identifier that is not invalidated after authentication.",
                "0.6.0", "CWE-384", null);

        cve(webpack, "GHSA-hc6q-2mpp-qw7j", "CVE-2023-28154", RiskLevel.LOW, 3.7,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N",
                "Webpack Source Map Disclosure",
                "webpack before 5.76.0 can expose source maps containing original source files under certain configuration.",
                "5.76.0", null, null);

        cve(socketIoParser, "GHSA-qm95-pgcg-qqfq", "CVE-2023-32695", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "socket.io-parser Denial of Service via Large Packet",
                "A specially crafted Socket.IO packet sent to a vulnerable server can cause an uncaught exception and server crash.",
                "4.2.3", "CWE-400", null);

        // ── CVEs for new PYPI ───────────────────────────────────────────

        cve(django, "GHSA-jh3w-4vvf-mjgr", "CVE-2022-34265", RiskLevel.HIGH, 9.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N",
                "Django SQL Injection via Trunc and Extract",
                "An issue was discovered in Django 3.2 before 3.2.14. The Trunc() and Extract() database functions are subject to SQL injection if untrusted data is used as a kind/lookup_name value.",
                "3.2.14", "CWE-89",
                "User-controlled lookup_name passed directly to database query. Always use hard-coded choices or validate against an allowlist.");

        cve(flask, "GHSA-m2qf-hxjv-5gpq", "CVE-2023-30861", RiskLevel.MEDIUM, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
                "Flask Session Cookie Without Secure Flag",
                "When all of: a non-default session interface that doesn't implement should_set_cookie, a permanent session, and not setting SESSION_REFRESH_EACH_REQUEST to False, Flask could fail to set the Secure flag on a session cookie.",
                "2.3.2", "CWE-614", null);

        cve(jinja2, "GHSA-h5c8-rqwp-cp95", "CVE-2024-22195", RiskLevel.HIGH, 6.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N",
                "Jinja2 Cross-Site Scripting via xmlattr Filter",
                "The xmlattr filter in Jinja2 does not escape keys, allowing for XSS when attribute names are user-controlled.",
                "3.1.3", "CWE-79",
                "Any Jinja2 template using the xmlattr filter with unsanitized keys is exploitable. Validate all attribute names before rendering.");

        cve(werkzeug, "GHSA-hrfv-mqp8-q5rw", "CVE-2023-25577", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                "Werkzeug DoS via multipart/form-data parsing",
                "Werkzeug multipart data parsing can use excessive memory or CPU time, leading to a denial of service. Applications that accept large multipart uploads are at risk.",
                "2.2.3", "CWE-400", null);

        cve(cryptography, "GHSA-x4qr-2fvf-3mr5", "CVE-2023-23931", RiskLevel.HIGH, 9.1,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N",
                "cryptography Bleichenbacher Timing Oracle Attack",
                "Previously, `Cipher.update_into` would accept Python objects which implement the buffer protocol, but provide only immutable buffers. This would allow immutable objects (like bytes) to be mutated, which is undefined behaviour.",
                "39.0.1", "CWE-327", null);

        cve(sqlalchemy, "GHSA-pgjx-7f9g-9463", "CVE-2023-30608", RiskLevel.MEDIUM, 6.5,
                "CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N",
                "SQLAlchemy SQL Injection via ORM Expression",
                "SQLAlchemy through 1.4.x does not prevent SQL injection in some raw string clauses when format() is used to construct the query.",
                "1.4.49", "CWE-89", null);

        cve(paramiko, "GHSA-f8q4-jwww-x3wv", "CVE-2022-24302", RiskLevel.MEDIUM, 5.9,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N",
                "Paramiko PreAuth Race Condition in Key Creation",
                "In Paramiko before 2.10.1, there is a race condition in the write_private_key_file function that causes data to be written in a world-readable file before the proper permissions are set.",
                "2.10.1", "CWE-362", null);

        cve(aiohttp, "GHSA-5h86-8mv2-5q98", "CVE-2023-47627", RiskLevel.HIGH, 7.5,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
                "aiohttp HTTP Header Injection",
                "aiohttp is vulnerable to HTTP request smuggling and header injection due to improper validation of HTTP chunked encoding.",
                "3.9.0", "CWE-74", null);

        cve(certifi, "GHSA-xqr8-7jwr-rhp7", "CVE-2022-23491", RiskLevel.MEDIUM, 6.8,
                "CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:N",
                "Certifi Outdated Root Certificates",
                "Certifi 2022.12.07 removes root certificates from TrustCor from the root store, as TrustCor was found to be issuing certificates in a way that undermines trust.",
                "2022.12.07", "CWE-295", null);

        cve(pandas, "GHSA-cmm4-g2f9-3q4g", "CVE-2023-28079", RiskLevel.LOW, 3.7,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:L",
                "pandas ReDoS via Timestamp Parsing",
                "A Regular Expression Denial of Service (ReDoS) vulnerability was found in pandas when parsing a specific timestamp format.",
                "1.5.3", "CWE-1333", null);

        cve(scipy, "GHSA-hq9p-rg4g-hpj7", "CVE-2023-25399", RiskLevel.LOW, 3.3,
                "CVSS:3.1/AV:L/AC:L/PR:N/UI:R/S:U/C:N/I:N/A:L",
                "scipy Memory Corruption via Crafted Array Input",
                "scipy before 1.10.0 is vulnerable to a memory corruption issue in the C extension code when processing large crafted inputs.",
                "1.10.0", null, null);

        cve(httpx, "GHSA-9x7f-gwxq-6f57", "CVE-2021-41945", RiskLevel.LOW, 3.7,
                "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N",
                "httpx SSRF via IPv6 Zone ID",
                "httpx before 0.23.0 does not properly handle IPv6 Zone IDs in URLs, potentially allowing SSRF via crafted URLs.",
                "0.23.0", null, null);

        cve(celery, "GHSA-q4xr-rc97-8qx3", "CVE-2021-23727", RiskLevel.MEDIUM, 7.5,
                "CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H",
                "Celery Command Injection via Untrusted Configuration",
                "Celery before 5.2.2 allows OS command injection via a crafted broker URL when using the --broker option.",
                "5.2.2", "CWE-78", null);

        // ================================
        // 5. Scan results — project A (8 history points, rich trend)
        // ================================

        // Oldest: only jackson issue
        ScanResult scanA1 = scan(projectA, "1.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(90));
        // Vulnerabilities increasing
        ScanResult scanA2 = scan(projectA, "1.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(70));
        // Introduce Log4Shell
        ScanResult scanA3 = scan(projectA, "2.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(50));
        // Apply hotfix — remove log4j + groovy
        ScanResult scanA4 = scan(projectA, "2.0.1-hotfix", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(40));
        // Spring4Shell + new infrastructure libraries
        ScanResult scanA5 = scan(projectA, "2.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(25));
        // Large security cleanup
        ScanResult scanA6 = scan(projectA, "3.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(14));
        // Small regression (groovy reintroduced)
        ScanResult scanA7 = scan(projectA, "3.1.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(7));
        // Current — cleaned up
        ScanResult scanA8 = scan(projectA, "3.2.0", ScanStatus.COMPLETED, LocalDateTime.now().minusHours(1));
        // Scan failure (error-state edge case)
        ScanResult scanAFail = scan(projectA, "3.2.0-rc1", ScanStatus.FAILED, LocalDateTime.now().minusDays(2));
        scanAFail.fail("deps.dev API timed out after 30 seconds — retryable error");
        // Pending scan (in-progress banner edge case)
        scan(projectA, "3.3.0-SNAPSHOT", ScanStatus.PENDING, LocalDateTime.now().minusMinutes(5));
        // SCANNING — package list saved, waiting for vulnerability analysis (edge case)
        scan(projectA, "3.3.0-rc1", ScanStatus.SCANNING, LocalDateTime.now().minusMinutes(3));
        // ANALYZING — fetching CVE/license data from deps.dev + OSV (edge case)
        scan(projectA, "3.3.0-rc2", ScanStatus.ANALYZING, LocalDateTime.now().minusMinutes(1));

        // ================================
        // 6. Scan results — projects B & C
        // ================================

        ScanResult scanB1 = scan(projectB, "1.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(60));
        ScanResult scanB2 = scan(projectB, "1.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(30));
        ScanResult scanB3 = scan(projectB, "2.0.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(14));
        // Latest projectB scan linked to Quick Import user (submittedByUserId edge case)
        ScanResult scanB4 = scanByUser(projectB, "2.1.0", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(1), 1L);

        ScanResult scanC1 = scan(projectC, "0.5.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(45));
        ScanResult scanC2 = scan(projectC, "0.6.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(30));
        ScanResult scanC3 = scan(projectC, "0.7.0", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(15));
        ScanResult scanC4 = scan(projectC, "0.8.3", ScanStatus.COMPLETED, LocalDateTime.now().minusDays(3));

        // GitLab project — single completed scan (sanity check for VCS provider variation)
        ScanResult scanE1 = scanByUser(projectE, "1.4.2", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(2), 1L);

        // Trash project — preserve historical scans on soft-deleted records
        ScanResult scanTrash1 = scan(projectTrash, "0.9.0", ScanStatus.COMPLETED,
                LocalDateTime.now().minusDays(60));

        // ================================
        // 7. Scan components — project A (MAVEN / backend-api)
        //     Risk Trend target:
        //       v1.0.0: 0C  3H  4M 1L  (baseline, clean)
        //       v1.5.0: 3C  3H  5M 2L  (bad dependencies introduced)
        //       v2.0.0: 9C  5H  5M 2L  (Log4Shell spike — peak)
        //       v2.0.1: 5C  5H  5M 2L  (hotfix, partial improvement)
        //       v2.5.0: 7C  7H  6M 2L  (new feature, Spring4Shell)
        //       v3.0.0: 2C  5H  4M 1L  (large cleanup)
        //       v3.1.0: 3C  6H  4M 1L  (small regression)
        //       v3.2.0: 2C  4H  3M 1L  (current state)
        // ================================

        // scanA1 — 11 libraries, 0C 3H 4M 1L
        addComp(scanA1, jackson,          "Direct (1)",       false, false); // HIGH
        addComp(scanA1, hibernate,        "Direct (1)",       false, false); // MED + CAUTION lic
        addComp(scanA1, okhttp,           "Direct (1)",       false, false); // MED
        addComp(scanA1, gson,             "Direct (1)",       false, false); // LOW
        addComp(scanA1, guava,            "Transitive (2)",   false, false); // NONE
        addComp(scanA1, commonsIo,        "Direct (1)",       false, false); // HIGH
        addComp(scanA1, woodstox,         "Transitive (2)",   false, false); // HIGH
        addComp(scanA1, bouncycastle,     "Direct (1)",       false, false); // MED
        addComp(scanA1, pdfbox,           "Direct (1)",       false, false); // MED
        addComp(scanA1, unknownLic,       "Transitive (1)",   false, false); // UNKNOWN lic
        addComp(scanA1, gplLib,           "Direct (1)",       false, false); // RESTRICTED lic

        // scanA2 — 15 libraries, 3C 3H 5M 2L
        addComp(scanA2, jackson,          "Direct (1)",       false, false);
        addComp(scanA2, hibernate,        "Direct (1)",       false, false);
        addComp(scanA2, okhttp,           "Direct (1)",       false, false);
        addComp(scanA2, gson,             "Direct (1)",       false, false);
        addComp(scanA2, guava,            "Transitive (2)",   false, false);
        addComp(scanA2, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA2, woodstox,         "Transitive (2)",   false, false);
        addComp(scanA2, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA2, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA2, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA2, gplLib,           "Direct (1)",       false, false);
        addComp(scanA2, snakeYaml,        "Direct (1)",       false, false); // +CRIT
        addComp(scanA2, commonsText,      "Direct (1)",       false, false); // +CRIT+MED+LOW
        addComp(scanA2, commonsCollections, "Transitive (2)", false, false); // +CRIT
        addComp(scanA2, poiOoxml,         "Direct (1)",       false, false); // +MED

        // scanA3 — 22 libraries, 9C 5H 5M 2L [peak]
        addComp(scanA3, jackson,          "Direct (1)",       false, false);
        addComp(scanA3, hibernate,        "Direct (1)",       false, false);
        addComp(scanA3, okhttp,           "Direct (1)",       false, false);
        addComp(scanA3, gson,             "Direct (1)",       false, false);
        addComp(scanA3, guava,            "Transitive (2)",   false, false);
        addComp(scanA3, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA3, woodstox,         "Transitive (2)",   false, false);
        addComp(scanA3, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA3, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA3, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA3, gplLib,           "Direct (1)",       false, false);
        addComp(scanA3, snakeYaml,        "Direct (1)",       false, false);
        addComp(scanA3, commonsText,      "Direct (1)",       false, false);
        addComp(scanA3, commonsCollections, "Transitive (2)", false, false);
        addComp(scanA3, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA3, log4j,            "Direct (1)",       false, false); // +2 CRIT (log4shell)
        addComp(scanA3, xstream,          "Direct (1)",       false, false); // +CRIT
        addComp(scanA3, groovyAll,        "Direct (1)",       false, false); // +CRIT
        addComp(scanA3, springWeb,        "Direct (1)",       false, false); // +CRIT+HIGH
        addComp(scanA3, springCore,       "Transitive (2)",   false, false); // +HIGH
        addComp(scanA3, h2Rce,            "Direct (1)",       false, false); // +CRIT
        addComp(scanA3, tomcatEmbed,      "Direct (1)",       false, false); // +HIGH

        // scanA4 — 20 libraries, 5C 5H 5M 2L (hotfix)
        // Removed: log4j (2C), groovyAll (1C)
        addComp(scanA4, jackson,          "Direct (1)",       false, false);
        addComp(scanA4, hibernate,        "Direct (1)",       false, false);
        addComp(scanA4, okhttp,           "Direct (1)",       false, false);
        addComp(scanA4, gson,             "Direct (1)",       false, false);
        addComp(scanA4, guava,            "Transitive (2)",   false, false);
        addComp(scanA4, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA4, woodstox,         "Transitive (2)",   false, false);
        addComp(scanA4, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA4, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA4, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA4, gplLib,           "Direct (1)",       false, false);
        addComp(scanA4, snakeYaml,        "Direct (1)",       false, false);
        addComp(scanA4, commonsText,      "Direct (1)",       false, false);
        addComp(scanA4, commonsCollections, "Transitive (2)", false, false);
        addComp(scanA4, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA4, xstream,          "Direct (1)",       false, false);
        addComp(scanA4, springWeb,        "Direct (1)",       false, false);
        addComp(scanA4, springCore,       "Transitive (2)",   false, false);
        addComp(scanA4, h2Rce,            "Direct (1)",       false, false);
        addComp(scanA4, tomcatEmbed,      "Direct (1)",       false, false);

        // scanA5 — 24 libraries, 7C 7H 6M 2L (new feature)
        // Added: hsqldb (1C), springSecCore (1H), nettyHandler (1H), notFetched
        addComp(scanA5, jackson,          "Direct (1)",       false, false);
        addComp(scanA5, hibernate,        "Direct (1)",       false, false);
        addComp(scanA5, okhttp,           "Direct (1)",       false, false);
        addComp(scanA5, gson,             "Direct (1)",       false, false);
        addComp(scanA5, guava,            "Transitive (2)",   false, false);
        addComp(scanA5, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA5, woodstox,         "Transitive (2)",   false, false);
        addComp(scanA5, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA5, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA5, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA5, gplLib,           "Direct (1)",       false, false);
        addComp(scanA5, snakeYaml,        "Direct (1)",       false, false);
        addComp(scanA5, commonsText,      "Direct (1)",       false, false);
        addComp(scanA5, commonsCollections, "Transitive (2)", false, false);
        addComp(scanA5, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA5, xstream,          "Direct (1)",       false, false);
        addComp(scanA5, springWeb,        "Direct (1)",       false, false);
        addComp(scanA5, springCore,       "Transitive (2)",   false, false);
        addComp(scanA5, h2Rce,            "Direct (1)",       false, false);
        addComp(scanA5, tomcatEmbed,      "Direct (1)",       false, false);
        addComp(scanA5, hsqldb,           "Direct (1)",       false, false); // +CRIT
        addComp(scanA5, springSecCore,    "Direct (1)",       false, false); // +HIGH
        addComp(scanA5, nettyHandler,     "Transitive (3)",   false, false); // +HIGH
        addComp(scanA5, notFetched,       "Transitive (1)",   false, false); // not enriched

        // scanA6 — 19 libraries, 2C 5H 4M 1L [large cleanup]
        // Removed: snakeYaml, commonsCollections, xstream, h2Rce, gplLib, notFetched, hsqldb, woodstox, tomcatEmbed
        // Added: internalLib, h2
        addComp(scanA6, jackson,          "Direct (1)",       true,  false); // reviewed=true
        addComp(scanA6, hibernate,        "Direct (1)",       false, false);
        addComp(scanA6, okhttp,           "Direct (1)",       false, false);
        addComp(scanA6, gson,             "Direct (1)",       false, false);
        addComp(scanA6, guava,            "Transitive (2)",   false, false);
        addComp(scanA6, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA6, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA6, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA6, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA6, commonsText,      "Direct (1)",       false, true);  // ignored=true
        addComp(scanA6, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA6, springWeb,        "Direct (1)",       false, false);
        addComp(scanA6, springCore,       "Transitive (2)",   false, false);
        addComp(scanA6, nettyHandler,     "Transitive (3)",   false, false);
        addComp(scanA6, springSecCore,    "Direct (1)",       false, false);
        addComp(scanA6, internalLib,      "Direct (1)",       false, false); // UNKNOWN lic
        addComp(scanA6, h2,              "Direct (1)",       false, false); // CAUTION lic

        // scanA7 — 21 libraries, 3C 6H 4M 1L [small regression]
        // Added: groovyAll reintroduced, commonsCollections brought back
        addComp(scanA7, jackson,          "Direct (1)",       true,  false);
        addComp(scanA7, hibernate,        "Direct (1)",       false, false);
        addComp(scanA7, okhttp,           "Direct (1)",       false, false);
        addComp(scanA7, gson,             "Direct (1)",       false, false);
        addComp(scanA7, guava,            "Transitive (2)",   false, false);
        addComp(scanA7, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA7, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA7, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA7, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA7, commonsText,      "Direct (1)",       false, false); // ignored lifted
        addComp(scanA7, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA7, springWeb,        "Direct (1)",       false, false);
        addComp(scanA7, springCore,       "Transitive (2)",   false, false);
        addComp(scanA7, nettyHandler,     "Transitive (3)",   false, false);
        addComp(scanA7, springSecCore,    "Direct (1)",       false, false);
        addComp(scanA7, internalLib,      "Direct (1)",       false, false);
        addComp(scanA7, h2,              "Direct (1)",       false, false);
        addComp(scanA7, groovyAll,        "Direct (1)",       false, false); // regression +CRIT
        addComp(scanA7, commonsCollections, "Transitive (2)", false, false); // regression +CRIT
        addComp(scanA7, woodstox,         "Transitive (2)",   false, false); // +HIGH
        addComp(scanA7, notFetched,       "Transitive (1)",   false, false);

        // scanA8 — 19 libraries, 2C 4H 3M 1L [current]
        // Removed: groovyAll, commonsCollections, woodstox, notFetched
        ScanComponent scA8SpringWeb   = addComp(scanA8, springWeb,       "Direct (1)",       false, false);
        ScanComponent scA8SpringCore  = addComp(scanA8, springCore,      "Transitive (2)",   false, false);
        ScanComponent scA8Jackson     = addComp(scanA8, jackson,         "Direct (1)",       true,  false);
        ScanComponent scA8Guava       = addComp(scanA8, guava,           "Transitive (3)",   false, false);
        addComp(scanA8, internalLib,     "Direct (1)",       false, false);
        ScanComponent scA8H2          = addComp(scanA8, h2,             "Direct (1)",       false, false);
        addComp(scanA8, commonsText,      "Direct (1)",       false, false);
        addComp(scanA8, hibernate,        "Direct (1)",       false, false);
        addComp(scanA8, okhttp,           "Direct (1)",       false, false);
        addComp(scanA8, gson,             "Direct (1)",       false, false);
        addComp(scanA8, commonsIo,        "Direct (1)",       false, false);
        addComp(scanA8, bouncycastle,     "Direct (1)",       false, false);
        addComp(scanA8, pdfbox,           "Direct (1)",       false, false);
        addComp(scanA8, unknownLic,       "Transitive (1)",   false, false);
        addComp(scanA8, poiOoxml,         "Direct (1)",       false, false);
        addComp(scanA8, nettyHandler,     "Transitive (3)",   false, false);
        addComp(scanA8, springSecCore,    "Direct (1)",       false, false);
        addComp(scanA8, tomcatEmbed,      "Direct (1)",       false, false);
        addComp(scanA8, gplLib,           "Direct (1)",       false, false); // RESTRICTED lic

        // ?? DependencyPaths for scanA8 ????????????????????????????????????

        savePath(scA8SpringWeb, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20")));

        savePath(scA8SpringCore, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("org.springframework:spring-core", "5.3.20")));

        savePath(scA8Jackson, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("com.fasterxml.jackson.core:jackson-databind", "2.13.0")));
        savePath(scA8Jackson, 1,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("com.fasterxml.jackson.core:jackson-databind", "2.13.0")));

        savePath(scA8Guava, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("org.springframework:spring-web", "5.3.20"),
                        node("org.springframework:spring-core", "5.3.20"),
                        node("com.google.guava:guava", "32.1.3-jre")));

        savePath(scA8H2, 0,
                List.of(node("backend-api", "3.2.0"),
                        node("com.h2database:h2", "2.1.214")));

        // ================================
        // 8. Scan components — project B (NPM / frontend-dashboard)
        //     Risk Trend target:
        //       v1.0.0:  0C  1H  3M 2L  (baseline)
        //       v1.5.0:  1C  4H  4M 2L  (dangerous libraries added)
        //       v2.0.0:  3C  7H  5M 2L  (peak — build-tool vulnerabilities)
        //       v2.1.0:  1C  3H  4M 2L  (cleaned up)
        // ================================

        // scanB1 — 8 libraries, 0C 1H 3M 2L
        addComp(scanB1, lodash,        "Direct (1)",     false, false); // HIGH
        addComp(scanB1, axios,         "Direct (1)",     false, false); // LOW
        addComp(scanB1, reactDom,      "Direct (1)",     false, false); // NONE
        addComp(scanB1, express,       "Direct (1)",     false, false); // MED
        addComp(scanB1, bodyParser,    "Direct (1)",     false, false); // MED
        addComp(scanB1, webpack,       "Transitive (3)", false, false); // LOW
        addComp(scanB1, semver,        "Transitive (2)", false, false); // MED
        addComp(scanB1, nodeFetch,     "Transitive (2)", false, false); // MED

        // scanB2 — 14 libraries, 1C 4H 4M 2L
        addComp(scanB2, lodash,        "Direct (1)",     false, false);
        addComp(scanB2, axios,         "Direct (1)",     false, false);
        addComp(scanB2, reactDom,      "Direct (1)",     false, false);
        addComp(scanB2, express,       "Direct (1)",     false, false);
        addComp(scanB2, bodyParser,    "Direct (1)",     false, false);
        addComp(scanB2, webpack,       "Transitive (3)", false, false);
        addComp(scanB2, semver,        "Transitive (2)", false, false);
        addComp(scanB2, nodeFetch,     "Transitive (2)", false, false);
        addComp(scanB2, momentJs,      "Direct (1)",     false, false); // MED
        addComp(scanB2, qs,            "Transitive (4)", false, false); // +CRIT
        addComp(scanB2, minimist,      "Transitive (3)", false, false); // +HIGH
        addComp(scanB2, ansiRegex,     "Transitive (5)", false, false); // +HIGH
        addComp(scanB2, json5,         "Transitive (4)", false, false); // +HIGH
        addComp(scanB2, toughCookie,   "Transitive (3)", false, false); // +HIGH

        // scanB3 — 19 libraries, 3C 7H 5M 2L [peak]
        addComp(scanB3, lodash,        "Direct (1)",     false, false);
        addComp(scanB3, axios,         "Direct (1)",     false, false);
        addComp(scanB3, reactDom,      "Direct (1)",     false, false);
        addComp(scanB3, express,       "Direct (1)",     false, false);
        addComp(scanB3, bodyParser,    "Direct (1)",     false, false);
        addComp(scanB3, webpack,       "Transitive (3)", false, false);
        addComp(scanB3, semver,        "Transitive (2)", false, false);
        addComp(scanB3, nodeFetch,     "Transitive (2)", false, false);
        addComp(scanB3, momentJs,      "Direct (1)",     false, false);
        addComp(scanB3, qs,            "Transitive (4)", false, false);
        addComp(scanB3, minimist,      "Transitive (3)", false, false);
        addComp(scanB3, ansiRegex,     "Transitive (5)", false, false);
        addComp(scanB3, json5,         "Transitive (4)", false, false);
        addComp(scanB3, toughCookie,   "Transitive (3)", false, false);
        addComp(scanB3, angularCore,   "Direct (1)",     false, false); // NONE
        addComp(scanB3, babelTraverse, "Transitive (4)", false, false); // +CRIT (RCE)
        addComp(scanB3, loaderUtils,   "Transitive (4)", false, false); // +CRIT (prototype)
        addComp(scanB3, socketIoParser,"Transitive (2)", false, false); // +HIGH
        addComp(scanB3, passport,      "Direct (1)",     false, false); // MED

        // scanB4 — 15 libraries, 1C 3H 4M 2L [cleaned up]
        // Removed: babelTraverse, loaderUtils, ansiRegex, socketIoParser
        ScanComponent scB4Lodash     = addComp(scanB4, lodash,      "Direct (1)",     true,  false); // reviewed
        ScanComponent scB4Axios      = addComp(scanB4, axios,       "Direct (1)",     false, false);
        ScanComponent scB4React      = addComp(scanB4, reactDom,    "Direct (1)",     false, false);
        ScanComponent scB4Express    = addComp(scanB4, express,     "Direct (1)",     false, false);
                                       addComp(scanB4, bodyParser,  "Direct (1)",     false, false);
                                       addComp(scanB4, webpack,     "Transitive (3)", false, false);
                                       addComp(scanB4, semver,      "Transitive (2)", false, false);
                                       addComp(scanB4, nodeFetch,   "Transitive (2)", false, false);
        ScanComponent scB4Moment     = addComp(scanB4, momentJs,    "Direct (1)",     true,  false); // reviewed
        ScanComponent scB4Qs         = addComp(scanB4, qs,          "Transitive (4)", false, true);  // ignored
                                       addComp(scanB4, minimist,    "Transitive (3)", false, false);
                                       addComp(scanB4, json5,       "Transitive (4)", false, false);
                                       addComp(scanB4, toughCookie, "Transitive (3)", false, false);
        ScanComponent scB4Angular    = addComp(scanB4, angularCore, "Direct (1)",     false, false);
                                       addComp(scanB4, passport,    "Direct (1)",     false, false);

        // DependencyPaths for scanB4
        savePath(scB4Lodash, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("lodash", "4.17.20")));

        savePath(scB4Axios, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("axios", "1.2.0")));

        savePath(scB4React, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("react-dom", "18.2.0")));

        savePath(scB4Express, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("express", "4.18.1")));

        savePath(scB4Moment, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("moment", "2.29.3")));
        savePath(scB4Moment, 1,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("@angular/core", "15.0.0"),
                        node("moment", "2.29.3")));

        savePath(scB4Qs, 0,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("axios", "1.2.0"),
                        node("qs", "6.5.2")));
        savePath(scB4Qs, 1,
                List.of(node("frontend-dashboard", "2.1.0"),
                        node("express", "4.18.1"),
                        node("qs", "6.5.2")));

        savePath(scB4Angular, 0,
                List.of(node("frontend-dashboard", "2.1.0"), node("@angular/core", "15.0.0")));

        // ================================
        // 9. Scan components — project C (Python / CLI / ml-pipeline)
        //     Risk Trend target:
        //       v0.5.0:  1C  1H  1M 2L  (baseline)
        //       v0.6.0:  1C  3H  3M 3L  (dependency growth)
        //       v0.7.0:  2C  7H  5M 3L  (peak — image + web libraries)
        //       v0.8.3:  2C  4H  4M 3L  (partial cleanup)
        // ================================

        // scanC1 — 7 libraries, 1C 1H 1M 2L
        addComp(scanC1, requests, "Direct (1)",   false, false); // HIGH
        addComp(scanC1, numpy,    "Direct (1)",   false, false); // NONE
        addComp(scanC1, pyyaml,   "Direct (1)",   false, false); // CRIT
        addComp(scanC1, scipy,    "Direct (1)",   false, false); // LOW
        addComp(scanC1, pandas,   "Direct (1)",   false, false); // LOW
        addComp(scanC1, flask,    "Direct (1)",   false, false); // MED
        addComp(scanC1, httpx,    "Transitive (1)",false, false);// LOW

        // scanC2 — 11 libraries, 1C 3H 3M 3L
        addComp(scanC2, requests,     "Direct (1)",    false, false);
        addComp(scanC2, numpy,        "Direct (1)",    false, false);
        addComp(scanC2, pyyaml,       "Direct (1)",    false, false);
        addComp(scanC2, scipy,        "Direct (1)",    false, false);
        addComp(scanC2, pandas,       "Direct (1)",    false, false);
        addComp(scanC2, flask,        "Direct (1)",    false, false);
        addComp(scanC2, httpx,        "Transitive (1)",false, false);
        addComp(scanC2, urllib3,      "Transitive (1)",false, false); // +MED
        addComp(scanC2, django,       "Direct (1)",    false, false); // +HIGH
        addComp(scanC2, cryptography, "Direct (1)",    false, false); // +HIGH
        addComp(scanC2, celery,       "Direct (1)",    false, false); // +MED

        // scanC3 — 17 libraries, 2C 7H 5M 3L [peak]
        addComp(scanC3, requests,     "Direct (1)",    false, false);
        addComp(scanC3, numpy,        "Direct (1)",    false, false);
        addComp(scanC3, pyyaml,       "Direct (1)",    false, false);
        addComp(scanC3, scipy,        "Direct (1)",    false, false);
        addComp(scanC3, pandas,       "Direct (1)",    false, false);
        addComp(scanC3, flask,        "Direct (1)",    false, false);
        addComp(scanC3, httpx,        "Transitive (1)",false, false);
        addComp(scanC3, urllib3,      "Transitive (1)",false, false);
        addComp(scanC3, django,       "Direct (1)",    false, false);
        addComp(scanC3, cryptography, "Direct (1)",    false, false);
        addComp(scanC3, celery,       "Direct (1)",    false, false);
        addComp(scanC3, pillow,       "Direct (1)",    false, false); // +CRIT+MED
        addComp(scanC3, jinja2,       "Transitive (2)",false, false); // +HIGH
        addComp(scanC3, werkzeug,     "Transitive (2)",false, false); // +HIGH
        addComp(scanC3, aiohttp,      "Direct (1)",    false, false); // +HIGH
        addComp(scanC3, certifi,      "Transitive (1)",false, false); // +MED (CAUTION lic)
        addComp(scanC3, paramiko,     "Direct (1)",    false, false); // +MED (CAUTION lic)

        // scanC4 — 15 libraries, 2C 4H 4M 3L [partial cleanup]
        // Removed: jinja2, werkzeug, paramiko
        ScanComponent scC4Requests = addComp(scanC4, requests,     "Direct (1)",    true,  false);
        ScanComponent scC4Numpy    = addComp(scanC4, numpy,        "Direct (1)",    false, false);
        ScanComponent scC4Pyyaml   = addComp(scanC4, pyyaml,       "Direct (1)",    false, false);
                                     addComp(scanC4, scipy,        "Direct (1)",    false, false);
                                     addComp(scanC4, pandas,       "Direct (1)",    false, false);
                                     addComp(scanC4, flask,        "Direct (1)",    false, false);
        ScanComponent scC4Urllib3  = addComp(scanC4, urllib3,      "Transitive (1)",false, false);
                                     addComp(scanC4, httpx,        "Transitive (1)",false, false);
                                     addComp(scanC4, django,       "Direct (1)",    false, false);
                                     addComp(scanC4, cryptography, "Direct (1)",    false, false);
                                     addComp(scanC4, celery,       "Direct (1)",    false, false);
        ScanComponent scC4Pillow   = addComp(scanC4, pillow,       "Direct (1)",    false, true);  // ignored
                                     addComp(scanC4, aiohttp,      "Direct (1)",    false, false);
                                     addComp(scanC4, certifi,      "Transitive (1)",false, false);
        ScanComponent scC4Sqlalchemy = addComp(scanC4, sqlalchemy, "Direct (1)",    false, false); // +MED

        // DependencyPaths for scanC4
        savePath(scC4Requests, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("requests", "2.28.0")));

        savePath(scC4Urllib3, 0,
                List.of(node("ml-pipeline", "0.8.3"),
                        node("requests", "2.28.0"),
                        node("urllib3", "1.26.5")));
        savePath(scC4Urllib3, 1,
                List.of(node("ml-pipeline", "0.8.3"),
                        node("aiohttp", "3.8.1"),
                        node("urllib3", "1.26.5")));

        savePath(scC4Pillow, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("Pillow", "9.0.0")));

        savePath(scC4Pyyaml, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("PyYAML", "5.3.1")));

        savePath(scC4Numpy, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("numpy", "1.26.4")));

        savePath(scC4Sqlalchemy, 0,
                List.of(node("ml-pipeline", "0.8.3"), node("SQLAlchemy", "1.4.40")));

        // ================================
        // 10. Scan components — project E (GitLab / payment-gateway)
        //     Minimal coverage so scans appear for VCS provider variation.
        // ================================

        addComp(scanE1, springWeb,     "Direct (1)",     false, false); // CRIT+HIGH
        addComp(scanE1, jackson,       "Direct (1)",     false, false); // HIGH
        addComp(scanE1, okhttp,        "Direct (1)",     false, false); // MED
        addComp(scanE1, guava,         "Transitive (2)", false, false); // NONE
        addComp(scanE1, bouncycastle,  "Direct (1)",     false, false); // MED
        addComp(scanE1, h2,            "Direct (1)",     false, false); // CAUTION lic

        // ================================
        // 11. Scan components — project TRASH (soft-deleted legacy-monolith)
        //     Preserve so real components appear in the UI when restored from trash.
        // ================================

        addComp(scanTrash1, log4j,     "Direct (1)",     false, false); // CRIT (Log4Shell)
        addComp(scanTrash1, jackson,   "Direct (1)",     false, false);
        addComp(scanTrash1, commonsIo, "Direct (1)",     false, false);
        addComp(scanTrash1, gplLib,    "Direct (1)",     false, false); // RESTRICTED lic

        // ================================
        // 12. Deferrals (exceptions) — edge cases by reason code
        // ================================

        // legal-review - indefinite (no expiration) — current backend-api RESTRICTED license
        deferComp(scanA8, gplLib, "legal-review", null,
                "Legal approved continued use until the Q3 migration is complete. See JIRA SEC-4521.");

        // false-positive - indefinite — Spring4Shell mitigated by WAF rules
        deferComp(scanA8, springWeb, "false-positive", null,
                "Not vulnerable to Spring4Shell: the app runs on JDK 8 and Tomcat 8 (CVE-2022-22965 requires JDK 9+).");

        // temporary - expires in 14 days — short-term h2 license deferral
        deferComp(scanA8, h2, "temporary", LocalDateTime.now().plusDays(14),
                "Temporary deferral: H2 is used only in local development/test profiles and will be removed before GA.");

        // wont-fix - already expired — show the "expired deferral" UI state
        deferComp(scanB4, qs, "wont-fix", LocalDateTime.now().minusDays(3),
                "Upgrade cost is greater than the risk for an internal-only admin UI. Re-evaluate next quarter.");

        // other - indefinite — free-text reason for ml-pipeline
        deferComp(scanC4, pillow, "other", null,
                "Tracked through security backlog item ML-882; no patched version is currently compatible with the TF stack.");

        return "redirect:/projects";
    }

    // ================================
    // Dedicated helper methods
    // ================================

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

    /** Variant that attributes the scan to a user (Quick Import / future user-level CLI auth). */
    private ScanResult scanByUser(Project project, String version, ScanStatus status,
                                  LocalDateTime scannedAt, Long submittedByUserId) {
        ScanResult s = ScanResult.builder()
                .project(project)
                .version(version)
                .status(status)
                .submittedByUserId(submittedByUserId)
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

    /**
     * Applies a deferral exception to the {@code library} ScanComponent within {@code scan}.
     * The component must already have been created through {@link #addComp}.
     */
    private void deferComp(ScanResult scan, Library library, String reason,
                           LocalDateTime expiresAt, String note) {
        scanComponentRepository.findByScanResultId(scan.getId()).stream()
                .filter(sc -> sc.getLibrary().getId().equals(library.getId()))
                .findFirst()
                .ifPresent(sc -> {
                    sc.applyDeferral(reason, expiresAt, note);
                    scanComponentRepository.save(sc);
                });
    }
}
