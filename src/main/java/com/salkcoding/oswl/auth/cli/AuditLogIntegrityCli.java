package com.salkcoding.oswl.auth.cli;

import com.salkcoding.oswl.auth.dto.AuditLogIntegrityReport;
import com.salkcoding.oswl.auth.service.AuditLogIntegrityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty("oswl.audit.integrity.verify-and-exit")
@RequiredArgsConstructor
public class AuditLogIntegrityCli implements CommandLineRunner {

    private final AuditLogIntegrityService integrityService;
    private final ApplicationContext context;

    @Value("${oswl.audit.integrity.batch-size:1000}")
    private int batchSize;

    @Override
    public void run(String... args) {
        AuditLogIntegrityReport report = integrityService.verify(null, batchSize);

        System.out.println("[audit-log-integrity] verified=" + report.isVerified()
                + " total=" + report.getTotal()
                + " unhashed=" + report.getUnhashedCount()
                + " broken=" + report.getBrokenCount()
                + (report.getFirstBrokenId() != null ? " firstBrokenId=" + report.getFirstBrokenId() : ""));

        if (!report.getBrokenIds().isEmpty()) {
            System.err.println("[audit-log-integrity] broken ids: " + report.getBrokenIds());
        }

        int exitCode = report.isVerified() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
