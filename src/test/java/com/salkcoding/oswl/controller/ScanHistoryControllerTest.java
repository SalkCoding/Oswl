package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ScanHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanHistoryController 단위 테스트")
class ScanHistoryControllerTest {

    @Mock ScanHistoryService scanHistoryService;
    @Mock ScanResultRepository scanResultRepository;
    @Mock AuditLogService auditLogService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks ScanHistoryController controller;

    @Test
    @DisplayName("index()는 scan-history/index 뷰를 반환한다")
    void index_returnsScanHistoryView() {
        Model model = new ConcurrentModel();

        String view = controller.index(1L, model);

        assertThat(view).isEqualTo("scan-history/index");
        verify(projectAccessService).assertCanViewProject(1L);
        verify(scanHistoryService).populateModel(1L, model);
    }

    @Test
    @DisplayName("deleteScan()은 스캔을 찾아 삭제하고 204를 반환한다")
    void deleteScan_foundScan_deletesAndReturns204() {
        ScanResult scan = mock(ScanResult.class);
        when(scanResultRepository.findByIdAndProjectId(42L, 1L)).thenReturn(Optional.of(scan));

        ResponseEntity<Void> response = controller.deleteScan(1L, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectAccessService).assertCanViewProject(1L);
        verify(scanResultRepository).delete(scan);
    }

    @Test
    @DisplayName("deleteScan()은 스캔이 없어도 204를 반환한다 (idempotent)")
    void deleteScan_scanNotFound_returns204() {
        when(scanResultRepository.findByIdAndProjectId(99L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.deleteScan(1L, 99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectAccessService).assertCanViewProject(1L);
        verify(scanResultRepository, never()).delete(any());
    }
}
