package com.salkcoding.oswl.controller.reporting;

import com.salkcoding.oswl.controller.spec.ReportBrandingControllerSpec;
import com.salkcoding.oswl.domain.entity.reporting.ReportBrandingSettings;
import com.salkcoding.oswl.dto.ReportBrandingResponse;
import com.salkcoding.oswl.dto.ReportBrandingUpdateRequest;
import com.salkcoding.oswl.service.reporting.ReportBrandingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/report-branding")
@PreAuthorize("hasPermission(null, 'SETTINGS_REPORTING_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ReportBrandingController implements ReportBrandingControllerSpec {

    private final ReportBrandingService reportBrandingService;

    @Override
    @GetMapping
    public ResponseEntity<ReportBrandingResponse> getSettings() {
        ReportBrandingSettings s = reportBrandingService.getSettings();
        return ResponseEntity.ok(new ReportBrandingResponse(
                s.getCompanyName(), s.getLogoDataUri(), s.getHeaderText(), s.isShowCoverPage()));
    }

    @Override
    @PutMapping
    public ResponseEntity<Void> saveSettings(@Valid @RequestBody ReportBrandingUpdateRequest request) {
        reportBrandingService.save(request.companyName(), request.logoDataUri(),
                request.headerText(), request.showCoverPage());
        return ResponseEntity.noContent().build();
    }
}
