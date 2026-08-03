package com.salkcoding.oswl.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streams the audit trail in SIEM-ingestible formats (JSON Lines / CEF).
 * Rows are paged through in chunks so arbitrarily large exports stay memory-bounded.
 */
@Service
@RequiredArgsConstructor
public class AuditLogSiemExportService {

    public static final String FORMAT_JSONL = "jsonl";
    public static final String FORMAT_CEF = "cef";

    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    // All serialized values are String/Long, so a plain mapper (no extra modules) is enough.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogRepository auditLogRepository;

    public boolean isSupported(String format) {
        return FORMAT_JSONL.equals(format) || FORMAT_CEF.equals(format);
    }

    /** Writes all matching audit entries (newest first) to {@code out}, one event per line. */
    @Transactional(readOnly = true)
    public void streamExport(AuditLogFilter filter, String format, OutputStream out) throws IOException {
        String actorEmail = isBlank(filter.getActorEmail()) ? null : filter.getActorEmail();
        String action = isBlank(filter.getAction()) ? null : filter.getAction();
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        int page = 0;
        Page<AuditLog> result;
        do {
            result = auditLogRepository.search(
                    filter.getStartDate(), filter.getEndDate(), actorEmail, action,
                    PageRequest.of(page, PAGE_SIZE));
            for (AuditLog entry : result.getContent()) {
                writer.write(FORMAT_CEF.equals(format) ? toCefLine(entry) : toJsonLine(entry));
                writer.write('\n');
            }
            writer.flush();
            page++;
        } while (result.hasNext());
    }

    // ── JSON Lines ───────────────────────────────────────────────────────────

    private String toJsonLine(AuditLog l) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.getId());
        row.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().format(TS) : null);
        row.put("actorEmail", l.getActorEmail());
        row.put("actorDisplayName", l.getActorDisplayName());
        row.put("actorIp", l.getActorIp());
        row.put("action", l.getAction());
        row.put("targetType", l.getTargetType());
        row.put("targetId", l.getTargetId());
        row.put("targetName", l.getTargetName());
        row.put("detail", l.getDetail());
        row.put("prevHash", l.getPrevHash());
        row.put("hash", l.getHash());
        return MAPPER.writeValueAsString(row);
    }

    // ── CEF (ArcSight Common Event Format) ───────────────────────────────────

    private String toCefLine(AuditLog l) {
        String action = l.getAction() != null ? l.getAction() : "UNKNOWN";
        long epochMillis = l.getCreatedAt() != null
                ? l.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : 0L;
        StringBuilder ext = new StringBuilder();
        ext.append("rt=").append(epochMillis);
        appendExt(ext, "suser", l.getActorEmail());
        appendExt(ext, "src", l.getActorIp());
        appendExt(ext, "act", l.getAction());
        appendExt(ext, "cs1Label", "Target Type");
        appendExt(ext, "cs1", l.getTargetType());
        appendExt(ext, "cs2Label", "Target Name");
        appendExt(ext, "cs2", l.getTargetName());
        appendExt(ext, "cs3Label", "Target ID");
        appendExt(ext, "cs3", l.getTargetId());
        appendExt(ext, "msg", l.getDetail());
        return "CEF:0|SalkCoding|OsWL|1.0|"
                + cefHeader(action) + '|'
                + cefHeader(action) + '|'
                + severityOf(action) + '|'
                + ext;
    }

    private void appendExt(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(' ').append(key).append('=').append(cefExtension(value));
    }

    /** Header fields escape backslash and pipe. */
    private String cefHeader(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\|");
    }

    /** Extension values escape backslash, equals sign and line breaks. */
    private String cefExtension(String s) {
        return s.replace("\\", "\\\\")
                .replace("=", "\\=")
                .replace("\r\n", " ")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** 0-10 CEF severity: destructive events rank higher, failures sit in the middle. */
    private int severityOf(String action) {
        String a = action.toUpperCase(Locale.ROOT);
        if (a.contains("FAIL") || a.contains("LOCK")) return 5;
        if (a.contains("DELETE") || a.contains("REVOKE") || a.contains("DEACTIVATE")) return 7;
        return 3;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
