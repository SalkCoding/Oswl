package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.BulkStatusRequest;
import com.salkcoding.oswl.service.SecurityCenterService;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityCenterController 단위 테스트")
class SecurityCenterControllerTest {

    @Mock SecurityCenterService securityCenterService;
    @InjectMocks SecurityCenterController controller;

    @Test
    @DisplayName("index: 서비스 호출 후 뷰 이름 반환")
    void index_callsServiceAndReturnsView() {
        String view = controller.index(1L, null, new ConcurrentModel());

        verify(securityCenterService).populateModel(eq(1L), isNull(), any());
        assertThat(view).isEqualTo("security-center/index");
    }

    @Test
    @DisplayName("index: scanId 파라미터가 서비스로 전달된다")
    void index_passesScanId() {
        controller.index(1L, 42L, new ConcurrentModel());

        verify(securityCenterService).populateModel(eq(1L), eq(42L), any());
    }

    @Test
    @DisplayName("bulkStatus: 서비스 호출 후 204 반환")
    void bulkStatus_callsServiceAndReturnsNoContent() {
        BulkStatusRequest req = new BulkStatusRequest(List.of(1L, 2L), true, null);

        ResponseEntity<Void> response = controller.bulkStatus(1L, req);

        verify(securityCenterService).bulkUpdateStatus(eq(1L), eq(req));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("export: csv 포맷이면 200 + Content-Disposition 반환")
    void export_csvFormat_returns200WithHeader() {
        byte[] data = "header,row\n".getBytes(StandardCharsets.UTF_8);
        when(securityCenterService.buildExportCsv(1L, null)).thenReturn(data);

        ResponseEntity<byte[]> response = controller.export(1L, null, "csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment; filename=");
        assertThat(response.getBody()).isEqualTo(data);
    }

    @Test
    @DisplayName("export: 지원하지 않는 포맷이면 400 반환")
    void export_unsupportedFormat_returns400() {
        ResponseEntity<byte[]> response = controller.export(1L, null, "xlsx");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("export: 대소문자 무관 'CSV'도 수락된다")
    void export_csvCaseInsensitive_returns200() {
        byte[] data = new byte[0];
        when(securityCenterService.buildExportCsv(1L, null)).thenReturn(data);

        ResponseEntity<byte[]> response = controller.export(1L, null, "CSV");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
