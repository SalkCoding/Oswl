package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.JiraSetting;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.dto.JiraSettingDto;
import com.salkcoding.oswl.repository.JiraSettingRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Jira issue-tracker integration (roadmap #10). Creates a Jira issue for a component's
 * vulnerabilities from the triage screen and stores the issue key/URL on the component.
 *
 * Uses the Jira REST v3 issue-create endpoint with Basic auth (email + API token); the
 * description is an Atlassian Document Format (ADF) body. Configuration is a single stored
 * row; the API token is encrypted at rest (same as VCS tokens).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraService {

    private final JiraSettingRepository jiraSettingRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final ProjectAccessService projectAccessService;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // ── Settings ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public JiraSettingDto getSettings() {
        return jiraSettingRepository.findFirstByOrderByIdAsc()
                .map(s -> new JiraSettingDto(s.getBaseUrl(), s.getEmail(),
                        s.getApiToken() != null && !s.getApiToken().isBlank(), // hasToken
                        s.getProjectKey(), s.getIssueType(), s.isEnabled()))
                .orElse(new JiraSettingDto(null, null, false, null, "Task", false));
    }

    @Transactional
    public void saveSettings(String baseUrl, String email, String apiTokenPlain,
                             String projectKey, String issueType, boolean enabled) {
        JiraSetting setting = jiraSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> JiraSetting.builder().build());
        String encToken = (apiTokenPlain != null && !apiTokenPlain.isBlank())
                ? encryptionService.encrypt(apiTokenPlain.strip())
                : null; // blank → keep existing (entity.update ignores blank)
        setting.update(normalizeUrl(baseUrl), email, encToken, projectKey, issueType, enabled);
        jiraSettingRepository.save(setting);
        auditLogService.log("JIRA.SETTINGS_UPDATE", "EXTERNAL_SETTING", "jira", null,
                "enabled=" + enabled + " project=" + projectKey);
    }

    // ── Issue creation ───────────────────────────────────────────────────

    public record JiraTicketResult(String issueKey, String issueUrl) {}

    /**
     * Creates a Jira issue for the component's vulnerabilities and links it on the component.
     * Idempotent-ish: if the component already has a linked issue, returns the existing link.
     */
    @Transactional
    public JiraTicketResult createTicket(Long projectId, Long componentId) {
        projectAccessService.assertCanViewProject(projectId);
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        if (sc.getJiraIssueKey() != null && sc.getJiraIssueUrl() != null) {
            return new JiraTicketResult(sc.getJiraIssueKey(), sc.getJiraIssueUrl());
        }

        JiraSetting setting = jiraSettingRepository.findFirstByOrderByIdAsc()
                .filter(JiraSetting::isConfigured)
                .orElseThrow(() -> new IllegalStateException(
                        "Jira is not configured. Set it up under Settings before creating tickets."));

        Library lib = sc.getLibrary();
        String token;
        try {
            token = encryptionService.decrypt(setting.getApiToken());
        } catch (Exception e) {
            throw new IllegalStateException("Stored Jira API token could not be decrypted. Re-save Jira settings.");
        }

        String summary = "[OsWL] Vulnerabilities in " + lib.getName() + " "
                + (lib.getVersion() != null ? lib.getVersion() : "");
        String payload = buildIssuePayload(setting.getProjectKey(), setting.getIssueType(), summary, sc);
        JsonNode created = postIssue(setting.getBaseUrl(), setting.getEmail(), token, payload);

        String key = created.path("key").asText(null);
        if (key == null) {
            throw new IllegalStateException("Jira did not return an issue key.");
        }
        String url = setting.getBaseUrl().replaceAll("/+$", "") + "/browse/" + key;
        sc.linkJiraIssue(key, url);
        scanComponentRepository.save(sc);

        auditLogService.log("COMPONENT.JIRA_TICKET", "COMPONENT", componentId.toString(),
                lib.getName() + " " + lib.getVersion(), "issue=" + key);
        log.info("[Jira] Created issue {} for projectId={} componentId={}", key, projectId, componentId);
        return new JiraTicketResult(key, url);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private String buildIssuePayload(String projectKey, String issueType, String summary, ScanComponent sc) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode fields = root.putObject("fields");
        fields.putObject("project").put("key", projectKey);
        fields.put("summary", summary);
        fields.putObject("issuetype").put("name", issueType != null ? issueType : "Task");

        // ADF description
        ObjectNode desc = fields.putObject("description");
        desc.put("type", "doc");
        desc.put("version", 1);
        ArrayNode content = desc.putArray("content");

        Library lib = sc.getLibrary();
        content.add(paragraph("Component: " + lib.getName() + " " + (lib.getVersion() != null ? lib.getVersion() : "")
                + " (" + lib.getEcosystem() + ")"));

        List<Cve> cves = lib.getCves().stream()
                .filter(c -> c.getSeverity() != null)
                .toList();
        if (cves.isEmpty()) {
            content.add(paragraph("No scored vulnerabilities are recorded for this component."));
        } else {
            content.add(paragraph("Vulnerabilities (" + cves.size() + "):"));
            ObjectNode bulletList = objectMapper.createObjectNode();
            bulletList.put("type", "bulletList");
            ArrayNode items = bulletList.putArray("content");
            for (Cve cve : cves) {
                String id = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
                String line = (id != null ? id : "unknown") + " — " + cve.getSeverity().name()
                        + (cve.getCvssScore() != null ? " (CVSS " + cve.getCvssScore() + ")" : "")
                        + (cve.getFixVersion() != null ? " — fix: " + cve.getFixVersion() : "");
                ObjectNode li = items.addObject();
                li.put("type", "listItem");
                li.putArray("content").add(paragraph(line));
            }
            content.add(bulletList);
        }
        content.add(paragraph("Created by OsWL."));
        return root.toString();
    }

    private ObjectNode paragraph(String text) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("type", "paragraph");
        ObjectNode t = p.putArray("content").addObject();
        t.put("type", "text");
        t.put("text", text);
        return p;
    }

    private JsonNode postIssue(String baseUrl, String email, String token, String payload) {
        String url = baseUrl.replaceAll("/+$", "") + "/rest/api/3/issue";
        String basic = Base64.getEncoder().encodeToString(
                (email + ":" + token).getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + basic)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("Jira authentication failed — check the email and API token.");
            }
            if (response.statusCode() >= 400) {
                log.warn("[Jira] create issue → {} body={}", response.statusCode(), response.body());
                throw new IllegalStateException("Jira API error " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Jira] create issue failed for {}: {}", url, e.getMessage());
            throw new IllegalStateException("Could not reach Jira: " + e.getMessage());
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return null;
        String u = url.strip();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u.replaceAll("/+$", "");
    }
}
