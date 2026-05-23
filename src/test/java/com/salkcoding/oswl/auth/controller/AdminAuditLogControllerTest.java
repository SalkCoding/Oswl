package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuditLogController unit tests")
class AdminAuditLogControllerTest {

    @Mock AuditLogService auditLogService;

    @InjectMocks AdminAuditLogController controller;

    // Inject @Value field
    private void setMaxPageSize(int size) {
        ReflectionTestUtils.setField(controller, "maxPageSize", size);
    }

    // -- list --

    @Test
    @DisplayName("list: returns page of audit logs")
    void list_returnsPage() {
        setMaxPageSize(200);
        AuditLogFilter filter = new AuditLogFilter();
        AuditLogDto dto = mock(AuditLogDto.class);
        Page<AuditLogDto> page = new PageImpl<>(List.of(dto));
        when(auditLogService.findAll(eq(filter), any(PageRequest.class))).thenReturn(page);

        Page<AuditLogDto> result = controller.list(filter, 0, 50);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("list: size capped at maxPageSize")
    void list_sizeCappedAtMaxPageSize() {
        setMaxPageSize(100);
        AuditLogFilter filter = new AuditLogFilter();
        when(auditLogService.findAll(eq(filter), eq(PageRequest.of(0, 100)))).thenReturn(Page.empty());

        controller.list(filter, 0, 500);

        verify(auditLogService).findAll(eq(filter), eq(PageRequest.of(0, 100)));
    }

    @Test
    @DisplayName("list: size below maxPageSize is used as-is")
    void list_sizeBelowMaxUsedAsIs() {
        setMaxPageSize(200);
        AuditLogFilter filter = new AuditLogFilter();
        when(auditLogService.findAll(eq(filter), eq(PageRequest.of(0, 30)))).thenReturn(Page.empty());

        controller.list(filter, 0, 30);

        verify(auditLogService).findAll(eq(filter), eq(PageRequest.of(0, 30)));
    }

    // -- exportCsv --

    @Test
    @DisplayName("exportCsv: returns 200 with CSV attachment")
    void exportCsv_returns200WithCsvBytes() {
        AuditLogFilter filter = new AuditLogFilter();
        byte[] csv = "col1,col2\nval1,val2\n".getBytes();
        when(auditLogService.exportCsv(filter)).thenReturn(csv);

        ResponseEntity<byte[]> result = controller.exportCsv(filter);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(csv);
        assertThat(result.getHeaders().getFirst("Content-Disposition"))
                .contains("audit-logs.csv");
        assertThat(result.getHeaders().getContentType().toString())
                .contains("text/csv");
    }
}
