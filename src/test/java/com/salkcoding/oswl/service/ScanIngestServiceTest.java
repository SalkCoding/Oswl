package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ComponentRepository;
import com.salkcoding.oswl.repository.CveRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanIngestServiceTest {

    @Mock ScanResultRepository scanResultRepository;
    @Mock ComponentRepository componentRepository;
    @Mock CveRepository cveRepository;
    @Mock ProjectRepository projectRepository;
    @Mock AiAnalysisService aiAnalysisService;

    @InjectMocks
    ScanIngestService scanIngestService;

    // ── 예외 처리 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하지 않는 프로젝트로 수신 시 IllegalArgumentException이 발생한다")
    void ingest_throwsIllegalArgument_whenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        ScanPayload payload = mock(ScanPayload.class);

        assertThatThrownBy(() -> scanIngestService.ingest(99L, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── 정상 저장 흐름 ────────────────────────────────────────────────────

    @Test
    @DisplayName("ingest 후 반환된 ScanResult 상태는 COMPLETED이다")
    void ingest_setsScanStatusToCompleted() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("2.0.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of());

        ScanResult result = scanIngestService.ingest(1L, payload);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(result.getVersion()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("컴포넌트와 CVE가 각각 저장된다")
    void ingest_savesComponentAndCve() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload.CvePayload cvePayload = mock(ScanPayload.CvePayload.class);
        when(cvePayload.getCveId()).thenReturn("CVE-2024-0001");
        when(cvePayload.getSeverity()).thenReturn("CRITICAL");
        when(cvePayload.getCvssScore()).thenReturn(9.5);
        when(cvePayload.getType()).thenReturn("RCE");

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("log4j");
        when(compPayload.getVersion()).thenReturn("2.14.0");
        when(compPayload.getPatchability()).thenReturn("patchable");
        when(compPayload.getLicenseStatus()).thenReturn("CAUTION");
        when(compPayload.getLicenseName()).thenReturn("Apache-2.0");
        when(compPayload.getCves()).thenReturn(List.of(cvePayload));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        ArgumentCaptor<OswlComponent> compCaptor = ArgumentCaptor.forClass(OswlComponent.class);
        verify(componentRepository).save(compCaptor.capture());
        OswlComponent savedComp = compCaptor.getValue();
        assertThat(savedComp.getName()).isEqualTo("log4j");
        assertThat(savedComp.getVersion()).isEqualTo("2.14.0");
        assertThat(savedComp.getPatchability()).isEqualTo(Patchability.PATCHABLE);
        assertThat(savedComp.getLicenseStatus()).isEqualTo(LicenseStatus.CAUTION);

        ArgumentCaptor<Cve> cveCaptor = ArgumentCaptor.forClass(Cve.class);
        verify(cveRepository).save(cveCaptor.capture());
        Cve savedCve = cveCaptor.getValue();
        assertThat(savedCve.getCveId()).isEqualTo("CVE-2024-0001");
        assertThat(savedCve.getSeverity()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(savedCve.getCvssScore()).isEqualTo(9.5);
    }

    @Test
    @DisplayName("여러 컴포넌트가 있으면 각각 저장된다")
    void ingest_savesMultipleComponents() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload.ComponentPayload comp1 = mock(ScanPayload.ComponentPayload.class);
        when(comp1.getName()).thenReturn("libA");
        when(comp1.getCves()).thenReturn(List.of());

        ScanPayload.ComponentPayload comp2 = mock(ScanPayload.ComponentPayload.class);
        when(comp2.getName()).thenReturn("libB");
        when(comp2.getCves()).thenReturn(List.of());

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of(comp1, comp2));

        scanIngestService.ingest(1L, payload);

        verify(componentRepository, times(2)).save(any(OswlComponent.class));
    }

    // ── enum 파싱 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("'non-patchable' 문자열은 NON_PATCHABLE, 'VIOLATION'은 LicenseStatus.RESTRICTED으로 파싱된다")
    void ingest_parsesNonPatchable_andViolation() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("badlib");
        when(compPayload.getPatchability()).thenReturn("non-patchable");
        when(compPayload.getLicenseStatus()).thenReturn("RESTRICTED");
        when(compPayload.getCves()).thenReturn(List.of());

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        ArgumentCaptor<OswlComponent> captor = ArgumentCaptor.forClass(OswlComponent.class);
        verify(componentRepository).save(captor.capture());
        assertThat(captor.getValue().getPatchability()).isEqualTo(Patchability.NON_PATCHABLE);
        assertThat(captor.getValue().getLicenseStatus()).isEqualTo(LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("null 문자열은 기본값(UNKNOWN, OK, NONE)으로 파싱된다")
    void ingest_parsesNullValues_toDefaults() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload.CvePayload cvePayload = mock(ScanPayload.CvePayload.class);
        when(cvePayload.getCveId()).thenReturn("CVE-X");
        when(cvePayload.getSeverity()).thenReturn(null);  // → NONE

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("lib");
        when(compPayload.getPatchability()).thenReturn(null);   // → UNKNOWN
        when(compPayload.getLicenseStatus()).thenReturn(null);  // → OK
        when(compPayload.getCves()).thenReturn(List.of(cvePayload));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        ArgumentCaptor<OswlComponent> compCaptor = ArgumentCaptor.forClass(OswlComponent.class);
        verify(componentRepository).save(compCaptor.capture());
        assertThat(compCaptor.getValue().getPatchability()).isEqualTo(Patchability.UNKNOWN);
        assertThat(compCaptor.getValue().getLicenseStatus()).isEqualTo(LicenseStatus.PERMITTED);

        ArgumentCaptor<Cve> cveCaptor = ArgumentCaptor.forClass(Cve.class);
        verify(cveRepository).save(cveCaptor.capture());
        assertThat(cveCaptor.getValue().getSeverity()).isEqualTo(RiskLevel.NONE);
    }

    @Test
    @DisplayName("CVE 심각도 문자열이 대소문자에 무관하게 파싱된다")
    void ingest_parsesCveSeverity_caseInsensitively() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(componentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload.CvePayload highCve = mock(ScanPayload.CvePayload.class);
        when(highCve.getCveId()).thenReturn("CVE-H");
        when(highCve.getSeverity()).thenReturn("high");  // 소문자

        ScanPayload.CvePayload medCve = mock(ScanPayload.CvePayload.class);
        when(medCve.getCveId()).thenReturn("CVE-M");
        when(medCve.getSeverity()).thenReturn("MEDIUM");

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("lib");
        when(compPayload.getCves()).thenReturn(List.of(highCve, medCve));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        ArgumentCaptor<Cve> cveCaptor = ArgumentCaptor.forClass(Cve.class);
        verify(cveRepository, times(2)).save(cveCaptor.capture());
        List<Cve> saved = cveCaptor.getAllValues();
        assertThat(saved).extracting(Cve::getSeverity)
                .containsExactlyInAnyOrder(RiskLevel.HIGH, RiskLevel.MEDIUM);
    }

    // ── AI 분석 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AI 미설정 시 AI 분석 메서드가 호출되지 않는다")
    void ingest_skipsAiAnalysis_whenNotConfigured() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(aiAnalysisService.isAiConfigured()).thenReturn(false);

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getRawJson()).thenReturn("{}");
        when(payload.getComponents()).thenReturn(List.of());

        scanIngestService.ingest(1L, payload);

        verify(aiAnalysisService).isAiConfigured();
        verify(aiAnalysisService, never()).summarizeCve(any(), any(), anyDouble(), any(), any());
        verify(aiAnalysisService, never()).summarizeLicenseRisk(any(), any(), any());
    }
}
