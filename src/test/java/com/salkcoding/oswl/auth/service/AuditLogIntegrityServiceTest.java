package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import com.salkcoding.oswl.security.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditLogIntegrityServiceTest {
    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditLogService hashing = spy(new AuditLogService(repository, mock(ClientIpResolver.class)));
    private final AuditLogIntegrityService verifier = new AuditLogIntegrityService(repository, hashing);

    AuditLogIntegrityServiceTest() {
        doNothing().when(hashing).log(any(), any(), any(), any(), any());
    }

    @Test
    void recomputesTheOnlyHashedRow() {
        var first = row(1, null);
        first.setDetail("altered after hashing");
        rows(first);
        var report = verifier.verify(null);
        assertThat(report.isVerified()).isFalse();
        assertThat(report.getBrokenIds()).containsExactly(1L);
    }

    @Test
    void acceptsAnIntactFirstRow() {
        rows(row(1, null));
        assertThat(verifier.verify(null).isVerified()).isTrue();
    }

    @Test
    void recomputesTheFirstFilteredRowEvenWhenItsPredecessorIsOutsideTheWindow() {
        var first = row(10, "outside-window");
        var filter = new AuditLogFilter();
        filter.setStartDate(LocalDateTime.of(2026, 1, 1, 0, 0));
        rows(first);
        assertThat(verifier.verify(filter).isVerified()).isTrue();
        first.setAction("ALTERED");
        assertThat(verifier.verify(filter).getBrokenIds()).containsExactly(10L);
    }

    @Test
    void endDateAloneDoesNotAllowAnUnexplainedPredecessor() {
        rows(row(1, "missing"));
        var filter = new AuditLogFilter();
        filter.setEndDate(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(verifier.verify(filter).getBrokenIds()).containsExactly(1L);
    }

    @Test
    void detectsEveryUnhashedRowAfterTheChainStartsAndItsBrokenContinuation() {
        var first = row(1, null);
        var second = row(2, first.getHash());
        var third = row(3, second.getHash());
        var fourth = row(4, third.getHash());
        second.setHash(null);
        third.setHash(null);
        rows(first, second, third, fourth);
        var report = verifier.verify(null);
        assertThat(report.getBrokenIds()).containsExactly(2L, 3L, 4L);
        assertThat(report.getBrokenCount()).isEqualTo(3);
        assertThat(report.getUnhashedCount()).isEqualTo(2);
    }

    @Test
    void legacyPrefixDoesNotExemptTheFirstHashedRowsContent() {
        var legacy = row(1, null);
        legacy.setHash(null);
        var first = row(2, null);
        first.setDetail("tampered");
        rows(legacy, first);
        assertThat(verifier.verify(null).getBrokenIds()).containsExactly(2L);
    }

    @Test
    void continuityAndContentFailuresAcrossPagesCountEachRowOnce() {
        var first = row(1, null);
        var second = row(2, "wrong predecessor");
        second.setDetail("also tampered");
        when(repository.findAllByFilterOrderByIdAsc(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first), PageRequest.of(0, 1), 2))
                .thenReturn(new PageImpl<>(List.of(second), PageRequest.of(1, 1), 2));
        var report = verifier.verify(null, 1);
        assertThat(report.getTotal()).isEqualTo(2);
        assertThat(report.getBrokenCount()).isEqualTo(1);
        assertThat(report.getBrokenIds()).containsExactly(2L);
    }

    private AuditLog row(long id, String previous) {
        var entry = AuditLog.builder().id(id).action("TEST").actorEmail("system")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).detail("original").prevHash(previous).build();
        entry.setHash(hashing.computeHash(entry));
        return entry;
    }

    private void rows(AuditLog... entries) {
        when(repository.findAllByFilterOrderByIdAsc(any(), any(), any())).thenReturn(new PageImpl<>(List.of(entries)));
    }
}
