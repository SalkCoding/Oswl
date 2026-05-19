package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.LicenseControllerSpec;
import com.salkcoding.oswl.dto.LicenseContextDto;
import com.salkcoding.oswl.service.LicenseService;
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

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        @RequestParam(required = false, defaultValue = "BINARY") String deployment,
                        @RequestParam(required = false, defaultValue = "false") boolean modified,
                        @RequestParam(required = false, defaultValue = "DYNAMIC") String linking,
                        Model model) {
        LicenseContextDto context = LicenseContextDto.builder()
                .deployment(normalizeDeployment(deployment))
                .modified(modified)
                .linking(normalizeLinking(linking))
                .build();
        licenseService.populateModel(projectId, scanId, context, model);
        return "license/index";
    }

    @GetMapping("/export/notice")
    public ResponseEntity<byte[]> exportNotice(@PathVariable Long projectId,
                                               @RequestParam(required = false) Long scanId) {
        LicenseService.ExportPayload payload = licenseService.buildNoticeFile(projectId, scanId);
        return downloadResponse(payload, MediaType.TEXT_PLAIN);
    }

    @GetMapping("/export/spdx")
    public ResponseEntity<byte[]> exportSpdx(@PathVariable Long projectId,
                                             @RequestParam(required = false) Long scanId) {
        LicenseService.ExportPayload payload = licenseService.buildSpdxSbom(projectId, scanId);
        return downloadResponse(payload, MediaType.TEXT_PLAIN);
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
