package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.LicenseControllerSpec;
import com.salkcoding.oswl.dto.LicenseContextDto;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.LicenseService;
import com.salkcoding.oswl.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/projects/{projectId}/license")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'LICENSE_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class LicenseController implements LicenseControllerSpec {

    private final LicenseService licenseService;
    private final AuditLogService auditLogService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        @RequestParam(required = false, defaultValue = "BINARY") String deployment,
                        @RequestParam(required = false, defaultValue = "false") boolean modified,
                        @RequestParam(required = false, defaultValue = "DYNAMIC") String linking,
                        Model model) {
        projectAccessService.assertCanViewProject(projectId);
        LicenseContextDto context = LicenseContextDto.builder()
                .deployment(normalizeDeployment(deployment))
                .modified(modified)
                .linking(normalizeLinking(linking))
                .build();
        licenseService.populateModel(projectId, scanId, context, model);
        return "license/index";
    }

    @GetMapping("/export/notice")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'LICENSE_EXPORT') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportNotice(@PathVariable Long projectId,
                                               @RequestParam(required = false) Long scanId) {
        projectAccessService.assertCanViewProject(projectId);
        LicenseService.ExportPayload payload = licenseService.buildNoticeFile(projectId, scanId);
        auditLogService.log("LICENSE.EXPORT", "PROJECT", projectId.toString(), payload.fileName(),
                "format=notice scanId=" + (scanId != null ? scanId : "latest"));
        return downloadResponse(payload, MediaType.TEXT_PLAIN);
    }

    @GetMapping("/export/spdx")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'LICENSE_EXPORT') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportSpdx(@PathVariable Long projectId,
                                             @RequestParam(required = false) Long scanId) {
        projectAccessService.assertCanViewProject(projectId);
        LicenseService.ExportPayload payload = licenseService.buildSpdxSbom(projectId, scanId);
        auditLogService.log("LICENSE.EXPORT", "PROJECT", projectId.toString(), payload.fileName(),
                "format=spdx scanId=" + (scanId != null ? scanId : "latest"));
        return downloadResponse(payload, MediaType.TEXT_PLAIN);
    }

    @GetMapping("/export/spdx-json")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'LICENSE_EXPORT') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportSpdxJson(@PathVariable Long projectId,
                                                 @RequestParam(required = false) Long scanId) {
        projectAccessService.assertCanViewProject(projectId);
        LicenseService.ExportPayload payload = licenseService.buildSpdxJsonSbom(projectId, scanId);
        auditLogService.log("LICENSE.EXPORT", "PROJECT", projectId.toString(), payload.fileName(),
                "format=spdx-json scanId=" + (scanId != null ? scanId : "latest"));
        return downloadResponse(payload, MediaType.APPLICATION_JSON);
    }

    @GetMapping("/export/cyclonedx")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'LICENSE_EXPORT') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportCycloneDx(@PathVariable Long projectId,
                                                @RequestParam(required = false) Long scanId) {
        projectAccessService.assertCanViewProject(projectId);
        LicenseService.ExportPayload payload = licenseService.buildCycloneDxJson(projectId, scanId);
        auditLogService.log("LICENSE.EXPORT", "PROJECT", projectId.toString(), payload.fileName(),
                "format=cyclonedx scanId=" + (scanId != null ? scanId : "latest"));
        return downloadResponse(payload, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<byte[]> downloadResponse(LicenseService.ExportPayload payload, MediaType type) {
        byte[] body = payload.body().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + payload.fileName() + "\"")
                .body(body);
    }

    private static String normalizeDeployment(String d) {
        if (d == null) return "BINARY";
        String u = d.trim().toUpperCase();
        return switch (u) {
            case "SAAS", "BINARY", "LIBRARY", "EMBEDDED" -> u;
            default -> "BINARY";
        };
    }

    private static String normalizeLinking(String l) {
        if (l == null) return "DYNAMIC";
        String u = l.trim().toUpperCase();
        return switch (u) {
            case "STATIC", "DYNAMIC" -> u;
            default -> "DYNAMIC";
        };
    }
}
