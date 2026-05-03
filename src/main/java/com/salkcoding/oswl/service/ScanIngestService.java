package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.*;
import com.salkcoding.oswl.repository.*;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Receives and persists scan results sent by the CLI client,
 * then triggers AI analysis as a post-processing step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanIngestService {

    private final ScanResultRepository scanResultRepository;
    private final ComponentRepository componentRepository;
    private final CveRepository cveRepository;
    private final ProjectRepository projectRepository;
    private final AiAnalysisService aiAnalysisService;

    /**
     * Receives and persists scan data from the CLI.
     * Authentication has already been completed by ApiKeyAuthInterceptor.
     *
     * @param projectId  project ID extracted from the authenticated API key
     * @param payload    payload DTO sent by the CLI
     */
    @Transactional
    public ScanResult ingest(Long projectId, ScanPayload payload) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // 1. Upsert ScanResult — same project + version → reuse existing row
        String incomingVersion = payload.getVersion();
        ScanResult scanResult = (incomingVersion != null)
                ? scanResultRepository.findByProjectIdAndVersion(projectId, incomingVersion)
                        .map(existing -> {
                            // Wipe old components so orphanRemoval cleans them up
                            existing.getComponents().clear();
                            existing.resetForRescan(payload.getRawJson());
                            return existing;
                        })
                        .orElseGet(() -> {
                            ScanResult s = ScanResult.builder()
                                    .project(project)
                                    .version(incomingVersion)
                                    .rawPayload(payload.getRawJson())
                                    .build();
                            return scanResultRepository.save(s);
                        })
                : ScanResult.builder()
                        .project(project)
                        .version(null)
                        .rawPayload(payload.getRawJson())
                        .build();

        if (incomingVersion == null) {
            scanResultRepository.save(scanResult);
        }

        // 2. Save components + CVEs
        if (payload.getComponents() != null) {
            for (ScanPayload.ComponentPayload cp : payload.getComponents()) {
                OswlComponent component = saveComponent(scanResult, cp);
                if (cp.getCves() != null) {
                    for (ScanPayload.CvePayload cv : cp.getCves()) {
                        saveCve(component, cv);
                    }
                }
            }
        }

        // 3. Transition scan to COMPLETED
        scanResult.complete();
        project.updateLastScanned(payload.getVersion(), LocalDateTime.now());

        // 4. AI analysis (only when configured)
        if (aiAnalysisService.isAiConfigured()) {
            runAiAnalysis(scanResult);
        }

        log.info("[ScanIngest] projectId={} version={} components={} status=COMPLETED",
                projectId, payload.getVersion(),
                payload.getComponents() != null ? payload.getComponents().size() : 0);

        return scanResult;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private OswlComponent saveComponent(ScanResult scanResult, ScanPayload.ComponentPayload cp) {
        OswlComponent component = OswlComponent.builder()
                .scanResult(scanResult)
                .name(cp.getName())
                .version(cp.getVersion())
                .dependencyInfo(cp.getDependencyInfo())
                .patchability(parsePatchability(cp.getPatchability()))
                .licenseStatus(parseLicenseStatus(cp.getLicenseStatus()))
                .licenseName(cp.getLicenseName())
                .build();
        return componentRepository.save(component);
    }

    private void saveCve(OswlComponent component, ScanPayload.CvePayload cv) {
        Cve cve = Cve.builder()
                .component(component)
                .cveId(cv.getCveId())
                .severity(parseRiskLevel(cv.getSeverity()))
                .cvssScore(cv.getCvssScore())
                .type(cv.getType())
                .discoveredOn(cv.getDiscoveredOn())
                .affects(cv.getAffects())
                .fixVersion(cv.getFixVersion())
                .build();
        cveRepository.save(cve);
    }

    private void runAiAnalysis(ScanResult scanResult) {
        try {
            for (OswlComponent comp : scanResult.getComponents()) {
                // CVE risk summary
                for (Cve cve : comp.getCves()) {
                    if (cve.getAiSummary() == null) {
                        String summary = aiAnalysisService.summarizeCve(
                                cve.getCveId(),
                                cve.getSeverity().name(),
                                cve.getCvssScore() != null ? cve.getCvssScore() : 0.0,
                                cve.getType(),
                                comp.getName() + " " + comp.getVersion());
                        cve.setAiSummary(summary);
                    }
                }
                // License risk summary
                if (comp.getLicenseStatus() != LicenseStatus.OK && comp.getAiLicenseSummary() == null) {
                    String summary = aiAnalysisService.summarizeLicenseRisk(
                            comp.getLicenseName(),
                            comp.getLicenseStatus().name(),
                            comp.getName());
                    comp.setAiLicenseSummary(summary);
                }
            }
        } catch (Exception e) {
            log.warn("[ScanIngest] AI analysis error (scan save unaffected): {}", e.getMessage());
        }
    }

    private Patchability parsePatchability(String value) {
        if (value == null) return Patchability.UNKNOWN;
        return switch (value.toLowerCase()) {
            case "patchable"     -> Patchability.PATCHABLE;
            case "non-patchable" -> Patchability.NON_PATCHABLE;
            default              -> Patchability.UNKNOWN;
        };
    }

    private LicenseStatus parseLicenseStatus(String value) {
        if (value == null) return LicenseStatus.OK;
        return switch (value.toUpperCase()) {
            case "WARN"      -> LicenseStatus.WARN;
            case "VIOLATION" -> LicenseStatus.VIOLATION;
            default          -> LicenseStatus.OK;
        };
    }

    private RiskLevel parseRiskLevel(String value) {
        if (value == null) return RiskLevel.NONE;
        return switch (value.toUpperCase()) {
            case "CRITICAL" -> RiskLevel.CRITICAL;
            case "HIGH"     -> RiskLevel.HIGH;
            case "MEDIUM"   -> RiskLevel.MEDIUM;
            case "LOW"      -> RiskLevel.LOW;
            default         -> RiskLevel.NONE;
        };
    }
}
