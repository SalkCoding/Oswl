package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.LicenseService;
import com.salkcoding.oswl.service.ProjectAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LicenseController 단위 테스트")
class LicenseControllerTest {

    @Mock LicenseService licenseService;
    @Mock AuditLogService auditLogService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks LicenseController controller;

    @Test
    @DisplayName("index: 서비스 호출 후 license/index 뷰 반환")
    void index_returnsLicenseView() {
        String view = controller.index(1L, null, "BINARY", false, "DYNAMIC", new ConcurrentModel());

        verify(projectAccessService).assertCanViewProject(1L);
        verify(licenseService).populateModel(eq(1L), isNull(), any(), any());
        assertThat(view).isEqualTo("license/index");
    }

    @Test
    @DisplayName("index: 대소문자 무관 SAAS deployment가 정상 처리된다")
    void index_saasDeployment_normalised() {
        controller.index(1L, null, "saas", false, "dynamic", new ConcurrentModel());

        verify(licenseService).populateModel(eq(1L), isNull(), argThat(ctx ->
                "SAAS".equals(ctx.getDeployment()) && "DYNAMIC".equals(ctx.getLinking())
        ), any());
    }

    @Test
    @DisplayName("exportNotice: 200 + Content-Disposition attachment 헤더 반환")
    void exportNotice_returnsAttachment() {
        LicenseService.ExportPayload payload =
                new LicenseService.ExportPayload("notice.txt", "NOTICE body");
        when(licenseService.buildNoticeFile(1L, null)).thenReturn(payload);

        ResponseEntity<byte[]> resp = controller.exportNotice(1L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("notice.txt");
    }

    @Test
    @DisplayName("exportSpdx: 200 + spdx 파일명 헤더 반환")
    void exportSpdx_returnsAttachment() {
        LicenseService.ExportPayload payload =
                new LicenseService.ExportPayload("sbom.spdx.json", "{}");
        when(licenseService.buildSpdxSbom(1L, null)).thenReturn(payload);

        ResponseEntity<byte[]> resp = controller.exportSpdx(1L, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("sbom.spdx.json");
    }
}
