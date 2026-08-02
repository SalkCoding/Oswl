package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.MailService;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.client.OsvClient.OsvQuery;
import com.salkcoding.oswl.client.OsvClient.OsvResult;
import com.salkcoding.oswl.client.OsvClient.OsvVuln;
import com.salkcoding.oswl.service.notification.WebhookNotificationService;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.CveAlert;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.repository.CveAlertRepository;
import com.salkcoding.oswl.repository.CveRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous monitoring: re-queries OSV for every component of each project's latest
 * completed scan and raises {@link CveAlert}s for vulnerabilities published after the scan.
 *
 * Runs without an outer transaction — the OSV batch call can take a while and must not
 * hold a DB connection; each repository call commits on its own (same pattern as
 * {@link VulnerabilityEnrichmentService#enrich}). Newly found vulnerabilities are also
 * upserted as {@link Cve} rows so Security Center reflects them immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContinuousMonitoringService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final CveRepository cveRepository;
    private final CveAlertRepository cveAlertRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final OsvClient osvClient;
    private final MailService mailService;
    private final AuditLogService auditLogService;
    private final WebhookNotificationService webhookNotificationService;

    /** Outcome of one monitoring cycle — logged and returned by the local dev trigger. */
    public record MonitoringSummary(
            int projectsChecked,
            int librariesChecked,
            int newCves,
            int alertsCreated,
            int emailsSent) {}

    /** Vulnerability discovered by the nightly re-query that was absent from the stored CVEs. */
    private record NewVuln(String vulnId, String cveId, String summary, String fixVersion, String cweId) {}

    /**
     * Runs one full monitoring cycle over all active projects.
     * Per-project failures are logged and skipped so one bad project cannot abort the cycle.
     */
    public MonitoringSummary runCycle() {
        log.info("[Monitor] Continuous monitoring cycle START");
        List<Project> projects = projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        // Phase 1: collect the latest completed scan components per project
        Map<Long, List<ScanComponent>> componentsByProject = new LinkedHashMap<>();
        for (Project project : projects) {
            try {
                List<ScanResult> latest = scanResultRepository.findRecentCompleted(project.getId(), 1);
                if (latest.isEmpty()) continue;
                List<ScanComponent> components =
                        scanComponentRepository.findByScanResultId(latest.getFirst().getId());
                if (!components.isEmpty()) {
                    componentsByProject.put(project.getId(), components);
                }
            } catch (Exception e) {
                log.error("[Monitor] Failed to load scan for projectId={}: {}",
                        project.getId(), e.getMessage());
            }
        }

        // Phase 2: dedup libraries across all projects and re-query OSV once per library
        Map<Long, Library> librariesById = new LinkedHashMap<>();
        for (List<ScanComponent> components : componentsByProject.values()) {
            for (ScanComponent sc : components) {
                librariesById.putIfAbsent(sc.getLibrary().getId(), sc.getLibrary());
            }
        }
        Map<Long, List<NewVuln>> newVulnsByLibrary = detectNewVulns(librariesById);
        int newCves = newVulnsByLibrary.values().stream().mapToInt(List::size).sum();

        // Phase 3: create alerts per project and notify members
        int alertsCreated = 0;
        int emailsSent = 0;
        for (Project project : projects) {
            List<ScanComponent> components = componentsByProject.get(project.getId());
            if (components == null) continue;
            try {
                List<CveAlert> alerts = createAlerts(project, components, newVulnsByLibrary);
                alertsCreated += alerts.size();
                if (!alerts.isEmpty()) {
                    emailsSent += notifyProjectMembers(project, alerts);
                }
            } catch (Exception e) {
                log.error("[Monitor] Failed to process alerts for projectId={}: {}",
                        project.getId(), e.getMessage(), e);
            }
        }

        MonitoringSummary summary = new MonitoringSummary(
                componentsByProject.size(), librariesById.size(), newCves, alertsCreated, emailsSent);
        log.info("[Monitor] Cycle DONE projects={} libraries={} newCves={} alerts={} emails={}",
                summary.projectsChecked(), summary.librariesChecked(),
                summary.newCves(), summary.alertsCreated(), summary.emailsSent());
        return summary;
    }

    // ── Phase 2: OSV re-query + Cve upsert ───────────────────────────────

    /**
     * Queries OSV for all libraries in one batch and returns the vulnerabilities that are
     * not yet stored on each library. New findings are persisted as Cve rows immediately
     * (severity NONE — same as the OSV-only path of the enrichment pipeline; the next full
     * scan enriches CVSS/severity from deps.dev).
     */
    private Map<Long, List<NewVuln>> detectNewVulns(Map<Long, Library> librariesById) {
        Map<Long, List<NewVuln>> result = new HashMap<>();
        if (librariesById.isEmpty()) return result;

        List<Library> libraries = new ArrayList<>(librariesById.values());
        List<OsvQuery> queries = libraries.stream()
                .map(lib -> new OsvQuery(
                        VulnerabilityEnrichmentService.toOsvEcosystem(lib.getEcosystem()),
                        lib.getName(),
                        lib.getVersion()))
                .toList();
        List<OsvResult> osvResults = osvClient.queryBatch(queries);

        for (int i = 0; i < libraries.size(); i++) {
            Library library = libraries.get(i);
            OsvResult osvResult = i < osvResults.size() ? osvResults.get(i) : null;
            if (osvResult == null || osvResult.vulns().isEmpty()) continue;

            List<NewVuln> newVulns = new ArrayList<>();
            for (OsvVuln ov : osvResult.vulns()) {
                boolean alreadyHave = library.getCves().stream().anyMatch(c ->
                        (ov.osvId() != null && ov.osvId().equals(c.getGhsaId()))
                        || (ov.cveId() != null && ov.cveId().equals(c.getCveId())));
                if (alreadyHave) continue;

                String vulnId = ov.osvId() != null ? ov.osvId() : ov.cveId();
                if (vulnId == null) continue;

                Cve cve = Cve.builder()
                        .library(library)
                        .ghsaId(ov.osvId())
                        .cveId(ov.cveId())
                        .cweId(ov.cweId())
                        .summary(ov.summary())
                        .fixVersion(ov.fixVersion())
                        .severity(RiskLevel.NONE)
                        .build();
                cveRepository.save(cve);
                library.getCves().add(cve);
                newVulns.add(new NewVuln(vulnId, ov.cveId(), ov.summary(), ov.fixVersion(), ov.cweId()));
                log.info("[Monitor] New vulnerability {} for {}:{} ({})",
                        vulnId, library.getName(), library.getVersion(), library.getEcosystem());
            }
            if (!newVulns.isEmpty()) {
                result.put(library.getId(), newVulns);
            }
        }
        return result;
    }

    // ── Phase 3: alerts + notification ───────────────────────────────────

    private List<CveAlert> createAlerts(Project project,
                                        List<ScanComponent> components,
                                        Map<Long, List<NewVuln>> newVulnsByLibrary) {
        List<CveAlert> created = new ArrayList<>();
        // A library can appear in multiple components of one scan — process each library once
        Map<Long, Library> seen = new LinkedHashMap<>();
        for (ScanComponent sc : components) {
            seen.putIfAbsent(sc.getLibrary().getId(), sc.getLibrary());
        }
        for (Library library : seen.values()) {
            List<NewVuln> newVulns = newVulnsByLibrary.get(library.getId());
            if (newVulns == null) continue;
            for (NewVuln nv : newVulns) {
                if (cveAlertRepository.existsByProjectIdAndLibraryIdAndVulnId(
                        project.getId(), library.getId(), nv.vulnId())) {
                    continue;
                }
                CveAlert alert = CveAlert.builder()
                        .project(project)
                        .library(library)
                        .libraryName(library.getName())
                        .libraryVersion(library.getVersion())
                        .ecosystem(library.getEcosystem())
                        .vulnId(nv.vulnId())
                        .cveId(nv.cveId())
                        .severity(RiskLevel.NONE)
                        .summary(nv.summary())
                        .fixVersion(nv.fixVersion())
                        .build();
                created.add(cveAlertRepository.save(alert));
            }
        }
        if (!created.isEmpty()) {
            String ids = created.stream()
                    .map(CveAlert::getVulnId)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            auditLogService.logAnonymous("[system]", "MONITOR.NEW_CVE", "PROJECT",
                    project.getId().toString(), project.getName(),
                    "newAlerts=" + created.size() + " vulns=" + truncate(ids, 400));
        }
        return created;
    }

    /** Sends the alert email to every enabled project member; returns the number of emails sent. */
    private int notifyProjectMembers(Project project, List<CveAlert> alerts) {
        List<Long> userIds = projectMemberRepository.findUserIdsByProjectId(project.getId());
        List<User> recipients = userRepository.findAllById(userIds).stream()
                .filter(User::isEnabled)
                .toList();
        if (recipients.isEmpty()) {
            log.warn("[Monitor] projectId={} has {} new alert(s) but no enabled members to notify",
                    project.getId(), alerts.size());
            return 0;
        }

        List<MailService.NewCveMailItem> items = alerts.stream()
                .map(a -> new MailService.NewCveMailItem(
                        a.getVulnId(), a.getCveId(),
                        a.getLibraryName(), a.getLibraryVersion(),
                        a.getFixVersion()))
                .toList();

        int sent = 0;
        for (User user : recipients) {
            boolean ok = mailService.sendNewCveAlert(
                    user.getEmail(), user.getDisplayName(), project.getName(), items);
            if (ok) sent++;
        }
        for (CveAlert alert : alerts) {
            alert.markNotified();
            cveAlertRepository.save(alert);
        }
        auditLogService.logAnonymous("[system]", "MONITOR.ALERT_EMAIL", "PROJECT",
                project.getId().toString(), project.getName(),
                "alerts=" + alerts.size() + " recipients=" + recipients.size() + " sent=" + sent);

        try {
            webhookNotificationService.sendNewCveAlert(project, alerts);
        } catch (Exception e) {
            log.error("[Monitor] Webhook notification failed for projectId={}: {}",
                    project.getId(), e.getMessage());
        }
        return sent;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
