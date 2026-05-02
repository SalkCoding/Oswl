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
 * CLI 클라이언트가 보낸 스캔 결과를 수신·저장하고
 * AI 분석을 비동기로 후처리한다.
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
     * CLI로부터 스캔 데이터를 수신하고 저장한다.
     * 인증은 ApiKeyAuthInterceptor에서 이미 완료된 상태이다.
     *
     * @param projectId  인증된 API 키에 포함된 프로젝트 ID
     * @param payload    CLI가 전송한 페이로드 DTO
     */
    @Transactional
    public ScanResult ingest(Long projectId, ScanPayload payload) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // 1. ScanResult 생성
        ScanResult scanResult = ScanResult.builder()
                .project(project)
                .version(payload.getVersion())
                .rawPayload(payload.getRawJson())
                .build();
        scanResultRepository.save(scanResult);

        // 2. 컴포넌트 + CVE 저장
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

        // 3. 스캔 완료 상태로 전환
        scanResult.complete();
        project.updateLastScanned(payload.getVersion(), LocalDateTime.now());

        // 4. AI 분석 (설정되어 있을 경우에만 실행)
        if (aiAnalysisService.isAiConfigured()) {
            runAiAnalysis(scanResult);
        }

        log.info("[ScanIngest] projectId={} version={} components={} status=COMPLETED",
                projectId, payload.getVersion(),
                payload.getComponents() != null ? payload.getComponents().size() : 0);

        return scanResult;
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

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
                // CVE 요약
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
                // 라이선스 위험 요약
                if (comp.getLicenseStatus() != LicenseStatus.OK && comp.getAiLicenseSummary() == null) {
                    String summary = aiAnalysisService.summarizeLicenseRisk(
                            comp.getLicenseName(),
                            comp.getLicenseStatus().name(),
                            comp.getName());
                    comp.setAiLicenseSummary(summary);
                }
            }
        } catch (Exception e) {
            log.warn("[ScanIngest] AI 분석 중 오류 (스캔 저장에는 영향 없음): {}", e.getMessage());
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
