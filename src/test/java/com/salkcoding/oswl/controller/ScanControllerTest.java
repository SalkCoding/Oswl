package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ScanIngestService;
import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanController (CLI integration) unit tests")
class ScanControllerTest {

    @Mock ScanIngestService       scanIngestService;
    @Mock ScanResultRepository    scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock AuditLogService         auditLogService;
    @Mock UserDetailsService      userDetailsService;
    @Mock PasswordEncoder         passwordEncoder;

    @InjectMocks ScanController scanController;

    private OswlUserPrincipal principalWithScanSubmit;
    private OswlUserPrincipal principalNoPermission;

    @BeforeEach
    void setUp() {
        principalWithScanSubmit = new OswlUserPrincipal(
                1L, "dev@company.com", "{noop}pass", "Dev User",
                false, true, List.of(), Set.of(), Set.of(Permission.SCAN_SUBMIT), false);

        principalNoPermission = new OswlUserPrincipal(
                2L, "readonly@company.com", "{noop}pass", "Read User",
                false, true, List.of(), Set.of(), Set.of(), false);
    }

    // ── ping ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ping returns 200 with status=ok and projectId")
    void ping_returns200() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(42L);

        var response = scanController.ping(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("ok");
        assertThat(response.getBody().getProjectId()).isEqualTo(42L);
    }

    // ── receiveScan ───────────────────────────────────────────────────

    @Test
    @DisplayName("receiveScan with valid credentials and permission returns 200 with scanId")
    void receiveScan_valid_returns200() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(1L);
        when(userDetailsService.loadUserByUsername("dev@company.com")).thenReturn(principalWithScanSubmit);
        when(passwordEncoder.matches("pass", principalWithScanSubmit.getPassword())).thenReturn(true);

        ScanResult result = mock(ScanResult.class);
        when(result.getId()).thenReturn(99L);
        when(result.getStatus()).thenReturn(ScanStatus.COMPLETED);
        when(scanIngestService.ingest(eq(1L), any())).thenReturn(result);

        ScanPayload payload = new ScanPayload();
        payload.setSubmitterEmail("dev@company.com");
        payload.setSubmitterPassword("pass");
        org.springframework.test.util.ReflectionTestUtils.setField(payload, "version", "1.2.3");

        ResponseEntity<ScanResponse> response = scanController.receiveScan(payload, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getScanId()).isEqualTo(99L);
        assertThat(response.getBody().getProjectId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("receiveScan with unknown email throws UnauthorizedException")
    void receiveScan_unknownEmail_throws() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(1L);
        when(userDetailsService.loadUserByUsername("unknown@company.com"))
                .thenThrow(new UsernameNotFoundException("not found"));

        ScanPayload payload = new ScanPayload();
        payload.setSubmitterEmail("unknown@company.com");
        payload.setSubmitterPassword("pass");
        org.springframework.test.util.ReflectionTestUtils.setField(payload, "version", "1.0");

        assertThatThrownBy(() -> scanController.receiveScan(payload, req))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("receiveScan with wrong password throws UnauthorizedException")
    void receiveScan_wrongPassword_throws() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(1L);
        when(userDetailsService.loadUserByUsername("dev@company.com")).thenReturn(principalWithScanSubmit);
        when(passwordEncoder.matches("wrongPass", principalWithScanSubmit.getPassword())).thenReturn(false);

        ScanPayload payload = new ScanPayload();
        payload.setSubmitterEmail("dev@company.com");
        payload.setSubmitterPassword("wrongPass");
        org.springframework.test.util.ReflectionTestUtils.setField(payload, "version", "1.0");

        assertThatThrownBy(() -> scanController.receiveScan(payload, req))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("receiveScan without SCAN_SUBMIT permission throws ForbiddenException")
    void receiveScan_noPermission_throws() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(1L);
        when(userDetailsService.loadUserByUsername("readonly@company.com")).thenReturn(principalNoPermission);
        when(passwordEncoder.matches("pass", principalNoPermission.getPassword())).thenReturn(true);

        ScanPayload payload = new ScanPayload();
        payload.setSubmitterEmail("readonly@company.com");
        payload.setSubmitterPassword("pass");
        org.springframework.test.util.ReflectionTestUtils.setField(payload, "version", "1.0");

        assertThatThrownBy(() -> scanController.receiveScan(payload, req))
                .isInstanceOf(ForbiddenException.class);
    }
}
