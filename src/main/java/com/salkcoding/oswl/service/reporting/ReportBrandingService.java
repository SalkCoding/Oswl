package com.salkcoding.oswl.service.reporting;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.reporting.ReportBrandingSettings;
import com.salkcoding.oswl.repository.reporting.ReportBrandingSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for the single-row report branding settings (logo, company name, header, cover page). */
@Service
@RequiredArgsConstructor
public class ReportBrandingService {

    /** ~500KB base64 ceiling — generous for a logo, small enough to keep the report page light. */
    private static final int MAX_LOGO_DATA_URI_LENGTH = 500_000;

    private final ReportBrandingSettingsRepository reportBrandingSettingsRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public ReportBrandingSettings getSettings() {
        return reportBrandingSettingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> ReportBrandingSettings.builder().build());
    }

    @Transactional
    public void save(String companyName, String logoDataUri, String headerText, boolean showCoverPage) {
        if (logoDataUri != null && !logoDataUri.isBlank()) {
            if (!logoDataUri.startsWith("data:image/")) {
                throw new IllegalArgumentException("Logo must be an image data URI");
            }
            if (logoDataUri.length() > MAX_LOGO_DATA_URI_LENGTH) {
                throw new IllegalArgumentException("Logo image is too large");
            }
        }

        ReportBrandingSettings settings = reportBrandingSettingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> ReportBrandingSettings.builder().build());
        settings.update(companyName, logoDataUri, headerText, showCoverPage);
        reportBrandingSettingsRepository.save(settings);

        auditLogService.log("REPORT_BRANDING.UPDATE", "EXTERNAL_SETTING", "report-branding", null,
                "companyName=" + (companyName != null && !companyName.isBlank())
                        + " logo=" + (logoDataUri != null && !logoDataUri.isBlank())
                        + " showCoverPage=" + showCoverPage);
    }
}
