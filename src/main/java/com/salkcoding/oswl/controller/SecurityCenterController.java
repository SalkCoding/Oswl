package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.SecurityCenterControllerSpec;
import com.salkcoding.oswl.dto.BulkStatusRequest;
import com.salkcoding.oswl.service.SecurityCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Controller
@RequestMapping("/projects/{projectId}/security-center")
@PreAuthorize("hasPermission(null, 'SECURITY_CENTER_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class SecurityCenterController implements SecurityCenterControllerSpec {

    private final SecurityCenterService securityCenterService;

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        Model model) {
        securityCenterService.populateModel(projectId, scanId, model);
        return "security-center/index";
    }

    @GetMapping("/print")
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_EXPORT') or hasRole('SYSTEM_ADMIN')")
    public String print(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        Model model) {
        securityCenterService.populateModel(projectId, scanId, model);
        return "security-center/print";
    }

    @PatchMapping("/bulk-status")
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> bulkStatus(@PathVariable Long projectId,
                                           @RequestBody BulkStatusRequest req) {
        securityCenterService.bulkUpdateStatus(projectId, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/export")
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_EXPORT') or hasRole('SYSTEM_ADMIN')")
    @ResponseBody
    public ResponseEntity<byte[]> export(@PathVariable Long projectId,
                                         @RequestParam(required = false) Long scanId,
                                         @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            return ResponseEntity.badRequest().build();
        }
        byte[] data = securityCenterService.buildExportCsv(projectId, scanId);
        String filename = "security-center-" + projectId + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(data);
    }
}
