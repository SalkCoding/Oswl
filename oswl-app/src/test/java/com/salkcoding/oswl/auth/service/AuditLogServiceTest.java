package com.salkcoding.oswl.auth.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService 단위 테스트")
class AuditLogServiceTest {

    @Mock AuditLogRepository auditLogRepository;

    @InjectMocks AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(auditLogService, "retentionMonths", 6);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── log ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("log: SecurityContext가 없으면 actorEmail이 'system'이다")
    void log_noSecurityContext_actorIsSystem() {
        SecurityContextHolder.clearContext();

        auditLogService.log("LOGIN", "USER", "1", "alice", null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertThat(cap.getValue().getActorEmail()).isEqualTo("system");
        assertThat(cap.getValue().getAction()).isEqualTo("LOGIN");
    }

    @Test
    @DisplayName("log: action/targetType/targetId/targetName이 엔티티에 저장된다")
    void log_fieldsArePersisted() {
        SecurityContextHolder.clearContext();

        auditLogService.log("DELETE", "PROJECT", "7", "MyProject", "detail-info");

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        AuditLog saved = cap.getValue();
        assertThat(saved.getAction()).isEqualTo("DELETE");
        assertThat(saved.getTargetType()).isEqualTo("PROJECT");
        assertThat(saved.getTargetId()).isEqualTo("7");
        assertThat(saved.getTargetName()).isEqualTo("MyProject");
        assertThat(saved.getDetail()).isEqualTo("detail-info");
    }

    // ── logAnonymous ──────────────────────────────────────────────────────

    @Test
    @DisplayName("logAnonymous: 주어진 actorEmail이 저장된다")
    void logAnonymous_usesGivenEmail() {
        auditLogService.logAnonymous("bob@example.com", "LOGIN.FAIL", "USER", null, null, null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertThat(cap.getValue().getActorEmail()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("logAnonymous: actorEmail이 null이면 'anonymous'가 저장된다")
    void logAnonymous_nullEmail_savesAnonymous() {
        auditLogService.logAnonymous(null, "LOGIN.FAIL", "USER", null, null, null);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(cap.capture());
        assertThat(cap.getValue().getActorEmail()).isEqualTo("anonymous");
    }

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll: 필터를 리포지토리로 위임하고 DTO 페이지를 반환한다")
    void findAll_delegatesToRepository() {
        AuditLog entity = buildAuditLog("USER.CREATE");
        Page<AuditLog> page = new PageImpl<>(List.of(entity));
        when(auditLogRepository.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        AuditLogFilter filter = new AuditLogFilter();
        filter.setActorEmail("admin@test.com");
        filter.setAction("USER.CREATE");

        Page<AuditLogDto> result = auditLogService.findAll(filter, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAction()).isEqualTo("USER.CREATE");
    }

    @Test
    @DisplayName("findAll: 필터 필드가 blank이면 null로 위임한다")
    void findAll_blankFilterFields_passedAsNull() {
        when(auditLogRepository.search(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        AuditLogFilter filter = new AuditLogFilter();
        filter.setActorEmail("  ");
        filter.setAction("");

        auditLogService.findAll(filter, Pageable.unpaged());

        verify(auditLogRepository).search(null, null, null, null, Pageable.unpaged());
    }

    // ── exportCsv ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("exportCsv: CSV 바이트 배열에 헤더 행이 포함된다")
    void exportCsv_containsHeaderRow() {
        when(auditLogRepository.search(any(), any(), any(), any(), eq(Pageable.unpaged())))
                .thenReturn(Page.empty());

        byte[] csv = auditLogService.exportCsv(new AuditLogFilter());
        String content = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(content).startsWith("createdAt,actorDisplayName,actorEmail");
    }

    @Test
    @DisplayName("exportCsv: 컴마를 포함한 필드는 따옴표로 감싸진다")
    void exportCsv_commaInField_quoted() {
        AuditLog entity = buildAuditLog("USER.CREATE");
        entity.setTargetName("Project, Alpha");
        entity.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));

        when(auditLogRepository.search(any(), any(), any(), any(), eq(Pageable.unpaged())))
                .thenReturn(new PageImpl<>(List.of(entity)));

        byte[] csv = auditLogService.exportCsv(new AuditLogFilter());
        String content = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(content).contains("\"Project, Alpha\"");
    }

    // ── purgeExpiredAuditLogs ─────────────────────────────────────────────

    @Test
    @DisplayName("purgeExpiredAuditLogs: 보존기간보다 오래된 로그를 삭제한다")
    void purgeExpiredAuditLogs_callsDeleteOlderThan() {
        when(auditLogRepository.deleteOlderThan(any(LocalDateTime.class))).thenReturn(5);

        auditLogService.purgeExpiredAuditLogs();

        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogRepository).deleteOlderThan(cap.capture());
        assertThat(cap.getValue()).isBeforeOrEqualTo(LocalDateTime.now().minusMonths(6));
    }

    // ── helper ────────────────────────────────────────────────────────────

    private AuditLog buildAuditLog(String action) {
        return AuditLog.builder()
                .id(1L)
                .actorEmail("admin@test.com")
                .actorDisplayName("Admin")
                .action(action)
                .targetType("USER")
                .targetId("1")
                .targetName("TestUser")
                .build();
    }
}
