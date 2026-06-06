package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Value("${oswl.audit.retention-months:6}")
    private int retentionMonths;

    /** Commits in a separate transaction so audit rows survive business rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String targetType, String targetId, String targetName, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email       = auth != null ? auth.getName() : "system";
        Long   actorId     = null;
        String displayName = null;
        if (auth != null && auth.getPrincipal() instanceof OswlUserPrincipal p) {
            actorId     = p.getUserId();
            displayName = p.getDisplayName();
        }
        auditLogRepository.save(AuditLog.builder()
                .actorEmail(email)
                .actorUserId(actorId)
                .actorDisplayName(displayName)
                .actorIp(resolveClientIp())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .detail(detail)
                .build());
    }

    /**
     * Writes a log entry by specifying actor information directly without a SecurityContext.
     * Used for events without an authentication context, such as login failures and initial setup.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAnonymous(String actorEmail, String action, String targetType,
                             String targetId, String targetName, String detail) {
        auditLogRepository.save(AuditLog.builder()
                .actorEmail(actorEmail != null ? actorEmail : "anonymous")
                .actorIp(resolveClientIp())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .detail(detail)
                .build());
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
            // Normalize IPv6 loopback to 127.0.0.1
            if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
            return ip;
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> findAll(AuditLogFilter filter, Pageable pageable) {
        return auditLogRepository.search(
                filter.getStartDate(),
                filter.getEndDate(),
                isBlank(filter.getActorEmail()) ? null : filter.getActorEmail(),
                isBlank(filter.getAction()) ? null : filter.getAction(),
                pageable
        ).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(AuditLogFilter filter) {
        Page<AuditLog> page = auditLogRepository.search(
                filter.getStartDate(),
                filter.getEndDate(),
                isBlank(filter.getActorEmail()) ? null : filter.getActorEmail(),
                isBlank(filter.getAction()) ? null : filter.getAction(),
                Pageable.unpaged()
        );
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(baos, false, StandardCharsets.UTF_8)) {
            pw.println("createdAt,actorDisplayName,actorEmail,actorIp,action,targetType,targetName,detail");
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            for (AuditLog l : page.getContent()) {
                pw.printf("%s,%s,%s,%s,%s,%s,%s,%s%n",
                        l.getCreatedAt() != null ? l.getCreatedAt().format(fmt) : "",
                        csv(l.getActorDisplayName()),
                        csv(l.getActorEmail()),
                        csv(l.getActorIp()),
                        csv(l.getAction()),
                        csv(l.getTargetType()),
                        csv(l.getTargetName()),
                        csv(l.getDetail()));
            }
        }
        return baos.toByteArray();
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String csv(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private AuditLogDto toDto(AuditLog l) {
        return AuditLogDto.builder()
                .id(l.getId())
                .actorDisplayName(l.getActorDisplayName())
                .actorEmail(l.getActorEmail())
                .actorIp(l.getActorIp())
                .action(l.getAction())
                .targetType(l.getTargetType())
                .targetName(l.getTargetName())
                .createdAt(l.getCreatedAt())
                .detail(l.getDetail())
                .build();
    }

    // ── Scheduled retention cleanup ──────────────────────────────────────────

    /**
     * Deletes audit log records older than {@code oswl.audit.retention-months} (default: 6 months).
     * Runs every day at 2:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void purgeExpiredAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(retentionMonths);
        int deleted = auditLogRepository.deleteOlderThan(cutoff);
        log.info("[AuditLog] Purged {} records older than {} months (cutoff={})",
                deleted, retentionMonths, cutoff);
    }
}
