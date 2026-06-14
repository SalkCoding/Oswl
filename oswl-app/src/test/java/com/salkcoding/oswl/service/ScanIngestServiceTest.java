package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.DependencyPathRepository;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanIngestServiceTest {

    @Mock ScanResultRepository scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock DependencyPathRepository dependencyPathRepository;
    @Mock LibraryRepository libraryRepository;
    @Mock ProjectRepository projectRepository;
    @Mock VulnerabilityEnrichmentService enrichmentService;
    @Mock ProjectCliKeyPolicyService projectCliKeyPolicyService;

    @InjectMocks
    ScanIngestService scanIngestService;

    private MockedStatic<TransactionSynchronizationManager> tsmMock;

    @BeforeEach
    void setUp() {
        tsmMock = mockStatic(TransactionSynchronizationManager.class);
    }

    @AfterEach
    void tearDown() {
        tsmMock.close();
    }

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
    @DisplayName("ingest 후 반환된 ScanResult 상태는 SCANNING이다")
    void ingest_setsScanStatusToScanning() {
        Project project = Project.builder().id(1L).name("P").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "2.0.0")).thenReturn(Optional.empty());
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("2.0.0");
        when(payload.getComponents()).thenReturn(List.of());

        ScanResult result = scanIngestService.ingest(1L, payload);

        assertThat(result.getStatus()).isEqualTo(ScanStatus.SCANNING);
        assertThat(result.getVersion()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("컴포넌트마다 Library를 조회하고 ScanComponent를 저장한다")
    void ingest_savesLibraryAndScanComponent() {
        Project project = Project.builder().id(1L).name("P").build();
        Library library = Library.builder()
                .name("log4j").version("2.14.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED).build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "1.0")).thenReturn(Optional.empty());
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(libraryRepository.findByNameAndVersionAndEcosystem("log4j", "2.14.0", "MAVEN"))
                .thenReturn(Optional.of(library));
        when(scanComponentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("log4j");
        when(compPayload.getVersion()).thenReturn("2.14.0");
        when(compPayload.getEcosystem()).thenReturn("MAVEN");
        when(compPayload.getDependencyInfo()).thenReturn("Direct (1)");
        when(compPayload.getDependencyPaths()).thenReturn(null);

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        ArgumentCaptor<ScanComponent> captor = ArgumentCaptor.forClass(ScanComponent.class);
        verify(scanComponentRepository).save(captor.capture());
        ScanComponent saved = captor.getValue();
        assertThat(saved.getLibrary()).isEqualTo(library);
        assertThat(saved.getDependencyInfo()).isEqualTo("Direct (1)");
    }

    @Test
    @DisplayName("Library가 없으면 새로 생성해서 저장한다")
    void ingest_createsLibrary_whenNotFound() {
        Project project = Project.builder().id(1L).name("P").build();
        Library createdLibrary = Library.builder()
                .name("newlib").version("1.0.0").ecosystem("NPM")
                .licenseStatus(LicenseStatus.UNKNOWN).build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "1.0")).thenReturn(Optional.empty());
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(libraryRepository.findByNameAndVersionAndEcosystem("newlib", "1.0.0", "NPM"))
                .thenReturn(Optional.empty());
        when(libraryRepository.save(any())).thenReturn(createdLibrary);
        when(scanComponentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScanPayload.ComponentPayload compPayload = mock(ScanPayload.ComponentPayload.class);
        when(compPayload.getName()).thenReturn("newlib");
        when(compPayload.getVersion()).thenReturn("1.0.0");
        when(compPayload.getEcosystem()).thenReturn("npm");
        when(compPayload.getDependencyPaths()).thenReturn(null);

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getComponents()).thenReturn(List.of(compPayload));

        scanIngestService.ingest(1L, payload);

        verify(libraryRepository).save(any(Library.class));
        verify(scanComponentRepository).save(any(ScanComponent.class));
    }

    @Test
    @DisplayName("여러 컴포넌트가 있으면 ScanComponent가 각각 저장된다")
    void ingest_savesMultipleScanComponents() {
        Project project = Project.builder().id(1L).name("P").build();
        Library libA = Library.builder().name("libA").version("1").ecosystem("MAVEN").build();
        Library libB = Library.builder().name("libB").version("1").ecosystem("MAVEN").build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "1.0")).thenReturn(Optional.empty());
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(libraryRepository.findByNameAndVersionAndEcosystem("libA", "1", "MAVEN")).thenReturn(Optional.of(libA));
        when(libraryRepository.findByNameAndVersionAndEcosystem("libB", "1", "MAVEN")).thenReturn(Optional.of(libB));
        when(scanComponentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScanPayload.ComponentPayload comp1 = mock(ScanPayload.ComponentPayload.class);
        when(comp1.getName()).thenReturn("libA");
        when(comp1.getVersion()).thenReturn("1");
        when(comp1.getEcosystem()).thenReturn("MAVEN");
        when(comp1.getDependencyPaths()).thenReturn(null);

        ScanPayload.ComponentPayload comp2 = mock(ScanPayload.ComponentPayload.class);
        when(comp2.getName()).thenReturn("libB");
        when(comp2.getVersion()).thenReturn("1");
        when(comp2.getEcosystem()).thenReturn("MAVEN");
        when(comp2.getDependencyPaths()).thenReturn(null);

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getComponents()).thenReturn(List.of(comp1, comp2));

        scanIngestService.ingest(1L, payload);

        verify(scanComponentRepository, times(2)).save(any(ScanComponent.class));
    }

    @Test
    @DisplayName("동일 버전으로 재수신 시 기존 ScanResult를 재사용한다")
    void ingest_reusesScanResult_whenVersionAlreadyExists() {
        Project project = Project.builder().id(1L).name("P").build();
        ScanResult existing = ScanResult.builder()
                .project(project).version("1.0").build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "1.0")).thenReturn(Optional.of(existing));
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getComponents()).thenReturn(List.of());

        ScanResult result = scanIngestService.ingest(1L, payload);

        assertThat(result).isSameAs(existing);
    }

    @Test
    @DisplayName("DependencyPath가 있으면 각 path마다 DependencyPath를 저장한다")
    void ingest_withDependencyPaths_savesDependencyPaths() {
        Project project = Project.builder().id(1L).name("P").build();
        Library library = Library.builder().name("lib").version("1.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.UNKNOWN).build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findByProjectIdAndVersion(1L, "1.0")).thenReturn(Optional.empty());
        when(scanResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(libraryRepository.findByNameAndVersionAndEcosystem("lib", "1.0", "MAVEN"))
                .thenReturn(Optional.of(library));
        when(scanComponentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dependencyPathRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Build a node ref stub
        ScanPayload.DependencyNodeRef node = mock(ScanPayload.DependencyNodeRef.class);
        when(node.getName()).thenReturn("root");
        when(node.getVersion()).thenReturn("1.0");

        ScanPayload.ComponentPayload comp = mock(ScanPayload.ComponentPayload.class);
        when(comp.getName()).thenReturn("lib");
        when(comp.getVersion()).thenReturn("1.0");
        when(comp.getEcosystem()).thenReturn("MAVEN");
        when(comp.getDependencyInfo()).thenReturn("Direct");
        when(comp.getDependencyPaths()).thenReturn(List.of(List.of(node)));

        ScanPayload payload = mock(ScanPayload.class);
        when(payload.getVersion()).thenReturn("1.0");
        when(payload.getComponents()).thenReturn(List.of(comp));

        scanIngestService.ingest(1L, payload);

        verify(dependencyPathRepository).save(any());
    }
}
