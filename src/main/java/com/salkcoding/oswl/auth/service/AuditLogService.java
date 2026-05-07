package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Async
    @Transactional
    public void log(String action, String targetType, String targetId, String targetName, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth != null ? auth.getName() : "system";
        Long actorId = null;
        if (auth != null && auth.getPrincipal() instanceof com.salkcoding.oswl.auth.security.OswlUserPrincipal p) {
            actorId = p.getUserId();
        }
        auditLogRepository.save(AuditLog.builder()
                .actorEmail(email)
                .actorUserId(actorId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .detail(detail)
                .build());
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
            pw.println("createdAt,actorEmail,action,targetType,targetName,detail");
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            for (AuditLog l : page.getContent()) {
                pw.printf("%s,%s,%s,%s,%s,%s%n",
                        l.getCreatedAt() != null ? l.getCreatedAt().format(fmt) : "",
                        csv(l.getActorEmail()),
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
                .actorEmail(l.getActorEmail())
                .action(l.getAction())
                .targetType(l.getTargetType())
                .targetName(l.getTargetName())
                .createdAt(l.getCreatedAt())
                .detail(l.getDetail())
                .build();
    }
}
