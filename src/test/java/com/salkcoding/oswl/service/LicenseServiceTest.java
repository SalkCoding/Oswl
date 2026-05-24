package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.LicenseContextDto;
import com.salkcoding.oswl.dto.LicenseConflictDto;
import com.salkcoding.oswl.dto.LicenseObligationGroupDto;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LicenseService 단위 테스트")
class LicenseServiceTest {

    @Mock ProjectRepository    projectRepository;
    @Mock ScanResultRepository scanResultRepository;
    @Mock LibraryRepository    libraryRepository;

    @InjectMocks LicenseService licenseService;

    private static final LicenseContextDto DEFAULT_CTX = LicenseContextDto.builder()
            .deployment("BINARY")
            .modified(false)
            .linking("DYNAMIC")
            .build();

    // ── populateModel ────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트 없으면 throws")
    void populateModel_projectNotFound_throws() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                licenseService.populateModel(99L, null, DEFAULT_CTX, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("완료된 스캔 없으면 빈 통계가 모델에 담긴다")
    void populateModel_noScans_emptyStats() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, DEFAULT_CTX, model);

        assertThat(model.getAttribute("totalLicenses")).isEqualTo(0);
        assertThat(model.getAttribute("restrictedCount")).isEqualTo(0);
        assertThat(model.getAttribute("obligations")).isEqualTo(List.of());
        assertThat(model.getAttribute("conflicts")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("MIT 라이브러리는 PERMITTED riskLevel로 집계된다")
    void populateModel_mitLibrary_isPermitted() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("commons-lang3").version("3.12.0").ecosystem("MAVEN")
                .licenseName("MIT")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, DEFAULT_CTX, model);

        assertThat(model.getAttribute("totalLicenses")).isEqualTo(1);
        assertThat(model.getAttribute("permittedCount")).isEqualTo(1L);
        assertThat(model.getAttribute("restrictedCount")).isEqualTo(0L);
    }

    @Test
    @DisplayName("GPL-2.0 라이브러리는 INCLUDE_LICENSE_NOTICE 의무가 생성된다 (BINARY 배포)")
    void populateModel_gpl2Library_obligationGenerated() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("some-gpl-lib").version("1.0").ecosystem("MAVEN")
                .licenseName("GPL-2.0")
                .licenseStatus(LicenseStatus.RESTRICTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, DEFAULT_CTX, model);

        @SuppressWarnings("unchecked")
        List<LicenseObligationGroupDto> obligations =
                (List<LicenseObligationGroupDto>) model.getAttribute("obligations");
        assertThat(obligations).isNotEmpty();
        assertThat(obligations.stream().map(LicenseObligationGroupDto::getKey))
                .contains("INCLUDE_LICENSE_NOTICE");
    }

    @Test
    @DisplayName("GPL-2.0 + Apache-2.0 조합은 충돌이 감지된다")
    void populateModel_gpl2AndApache_conflictDetected() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library gplLib = Library.builder()
                .id(1L).name("gpl-lib").version("1.0").ecosystem("MAVEN")
                .licenseName("GPL-2.0")
                .licenseStatus(LicenseStatus.RESTRICTED)
                .cves(List.of())
                .build();
        Library apacheLib = Library.builder()
                .id(2L).name("apache-lib").version("2.0").ecosystem("MAVEN")
                .licenseName("Apache-2.0")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(gplLib, apacheLib));

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, DEFAULT_CTX, model);

        @SuppressWarnings("unchecked")
        List<LicenseConflictDto> conflicts =
                (List<LicenseConflictDto>) model.getAttribute("conflicts");
        assertThat(conflicts).isNotEmpty();
        assertThat(conflicts.get(0).getSeverity()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("SAAS 배포 컨텍스트에서는 DISCLOSE_SOURCE 의무가 생성되지 않는다")
    void populateModel_saasDeployment_disclosureSourceNotRequired() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("gpl-lib").version("1.0").ecosystem("MAVEN")
                .licenseName("GPL-2.0")
                .licenseStatus(LicenseStatus.RESTRICTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        LicenseContextDto saasCtx = LicenseContextDto.builder()
                .deployment("SAAS")
                .modified(false)
                .linking("DYNAMIC")
                .build();

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, saasCtx, model);

        @SuppressWarnings("unchecked")
        List<LicenseObligationGroupDto> obligations =
                (List<LicenseObligationGroupDto>) model.getAttribute("obligations");
        assertThat(obligations.stream().map(LicenseObligationGroupDto::getKey))
                .doesNotContain("DISCLOSE_SOURCE");
    }

    @Test
    @DisplayName("LGPL + static linking 컨텍스트에서 ALLOW_RELINKING 의무가 생성된다")
    void populateModel_lgplStaticLinking_allowRelinkingObligation() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("lgpl-lib").version("2.1").ecosystem("MAVEN")
                .licenseName("LGPL-2.1")
                .licenseStatus(LicenseStatus.CAUTION)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        LicenseContextDto staticCtx = LicenseContextDto.builder()
                .deployment("BINARY")
                .modified(false)
                .linking("STATIC")
                .build();

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, staticCtx, model);

        @SuppressWarnings("unchecked")
        List<LicenseObligationGroupDto> obligations =
                (List<LicenseObligationGroupDto>) model.getAttribute("obligations");
        assertThat(obligations.stream().map(LicenseObligationGroupDto::getKey))
                .contains("ALLOW_RELINKING");
    }

    @Test
    @DisplayName("라이선스명 없는 라이브러리는 reviewItems에 MISSING으로 분류된다")
    void populateModel_missingLicense_appearsInReviewItems() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("mystery-lib").version("0.1").ecosystem("NPM")
                .licenseStatus(LicenseStatus.UNKNOWN)
                .cves(List.of())
                .build();  // licenseName is null
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        Model model = new ConcurrentModel();
        licenseService.populateModel(1L, null, DEFAULT_CTX, model);

        // Libraries without licenseName don't appear in licenses list
        assertThat(model.getAttribute("totalLicenses")).isEqualTo(0);
    }

    // ── buildNoticeFile ──────────────────────────────────────────────────

    @Test
    @DisplayName("buildNoticeFile: 프로젝트 없으면 throws")
    void buildNoticeFile_projectNotFound_throws() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> licenseService.buildNoticeFile(99L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buildNoticeFile: 스캔 없으면 헤더 포함된 파일 반환")
    void buildNoticeFile_noScans_headerOnly() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("TestProj").build()));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        LicenseService.ExportPayload result = licenseService.buildNoticeFile(1L, null);

        assertThat(result.body()).contains("THIRD-PARTY SOFTWARE NOTICES");
        assertThat(result.body()).contains("TestProj");
        assertThat(result.fileName()).contains("NOTICE");
    }

    @Test
    @DisplayName("buildNoticeFile: 라이브러리가 있으면 license 섹션이 포함된다")
    void buildNoticeFile_withLibrary_includesLicenseSection() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(Project.builder().id(1L).name("TestProj").build()));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("spring-core").version("6.0.0").ecosystem("MAVEN")
                .licenseName("Apache-2.0")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        LicenseService.ExportPayload result = licenseService.buildNoticeFile(1L, null);

        assertThat(result.body()).contains("License: Apache-2.0");
        assertThat(result.body()).contains("spring-core 6.0.0");
    }

    // ── buildSpdxSbom ────────────────────────────────────────────────────

    @Test
    @DisplayName("buildSpdxSbom: 프로젝트 없으면 throws")
    void buildSpdxSbom_projectNotFound_throws() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> licenseService.buildSpdxSbom(99L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buildSpdxSbom: 기본 SPDX 헤더가 생성된다")
    void buildSpdxSbom_noScans_spdxHeaderPresent() {
        Project project = Project.builder()
                .id(1L).name("MyApp")
                .build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        LicenseService.ExportPayload result = licenseService.buildSpdxSbom(1L, null);

        assertThat(result.body()).startsWith("SPDXVersion: SPDX-2.3");
        assertThat(result.body()).contains("DocumentName: MyApp");
        assertThat(result.fileName()).contains(".spdx");
    }

    @Test
    @DisplayName("buildSpdxSbom: 라이브러리마다 PackageName 행이 생성된다")
    void buildSpdxSbom_withLibraries_packageEntriesGenerated() {
        Project project = Project.builder()
                .id(1L).name("MyApp")
                .build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(1L).name("guava").version("32.0.0").ecosystem("MAVEN")
                .licenseName("Apache-2.0")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of())
                .build();
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        LicenseService.ExportPayload result = licenseService.buildSpdxSbom(1L, null);

        assertThat(result.body()).contains("PackageName: guava");
        assertThat(result.body()).contains("PackageVersion: 32.0.0");
        assertThat(result.body()).contains("PackageLicenseConcluded: Apache-2.0");
    }

    // ── helper ────────────────────────────────────────────────────────────

    private ScanResult buildScan(Long id, String version) {
        return ScanResult.builder()
                .id(id)
                .version(version)
                .status(ScanStatus.COMPLETED)
                .scannedAt(LocalDateTime.now())
                .build();
    }
}
