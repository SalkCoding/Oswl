package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.controller.spec.DiagnosticsControllerSpec;
import com.salkcoding.oswl.dto.diagnostics.DiagnosticCheckResult;
import com.salkcoding.oswl.service.diagnostics.DiagnosticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/settings/diagnostics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class DiagnosticsController implements DiagnosticsControllerSpec {

    private final DiagnosticsService diagnosticsService;

    @GetMapping
    public List<DiagnosticCheckResult> run(@AuthenticationPrincipal OswlUserPrincipal principal) {
        return diagnosticsService.runAll(principal != null ? principal.getUserId() : null);
    }
}
