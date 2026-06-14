package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.api.ScanParseResponse;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.api.ScanStatusResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.DependencyManifestParserService;
import com.salkcoding.oswl.service.ManifestArchiveService;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ScanApiCredentialThrottleService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanController (CLI integration) unit tests")
class ScanControllerTest {

    @Mock ScanIngestService scanIngestService;
    @Mock ScanResultRepository scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock AuditLogService auditLogService;
    @Mock UserDetailsService userDetailsService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ProjectAccessService projectAccessService;
    @Mock ScanApiCredentialThrottleService scanApiCredentialThrottleService;
    @Mock DependencyManifestParserService dependencyManifestParserService;
    @Mock ManifestArchiveService manifestArchiveService;

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

    @Test
    @DisplayName("parseManifests returns parsed components from uploaded archive")
    void parseManifests_returns200() throws Exception {
        Path extractDir = Path.of("extract");
        MultipartFile archive = new MockMultipartFile("archive", "manifests.zip",
                "application/zip", new byte[]{1, 2, 3});
        when(manifestArchiveService.extractToTempDir(archive)).thenReturn(extractDir);
        var component = ScanPayload.ComponentPayload.create("jackson-databind", "2.15.0", "MAVEN", "Direct", List.of());
        when(dependencyManifestParserService.parseDependencies(eq(extractDir), anyString()))
                .thenReturn(new DependencyManifestParserService.ParseResult("MAVEN", List.of(component)));

        var response = scanController.parseManifests(archive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEcosystem()).isEqualTo("MAVEN");
        assertThat(response.getBody().getComponentCount()).isEqualTo(1);
        verify(manifestArchiveService).deleteQuietly(extractDir);
    }

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

    @Test
    @DisplayName("receiveScan with valid credentials and permission returns 200 with scanId")
    void receiveScan_valid_returns200() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID)).thenReturn(1L);
        when(userDetailsService.loadUserByUsername("dev@company.com")).thenReturn(principalWithScanSubmit);
        when(passwordEncoder.matches("pass", principalWithScanSubmit.getPassword())).thenReturn(true);
        doNothing().when(projectAccessService).assertCanSubmitScan(1L, 1L);

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
        verify(scanApiCredentialThrottleService).recordCredentialSuccess(1L, "dev@company.com");
        verify(auditLogService).logAnonymous(eq("dev@company.com"), eq("SCAN.INGEST"),
                eq("PROJECT"), eq("1"), eq("1.2.3"), contains("scanId=99"));
    }

    @Test
    @DisplayName("receiveScan with unknown email throws UnauthorizedException and audits failure")
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
        verify(auditLogService).logAnonymous(eq("unknown@company.com"), eq("SCAN.AUTH_FAILURE"),
                eq("PROJECT"), eq("1"), eq("1.0"), contains("UNKNOWN_USER"));
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
        verify(auditLogService).logAnonymous(any(), eq("SCAN.AUTH_FAILURE"), any(), any(), any(),
                contains("INVALID_PASSWORD"));
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

    @Test
    @DisplayName("scanStatus: 존재하는 scanId는 200과 ScanStatusResponse를 반환한다")
    void scanStatus_found_returns200() {
        Project project = Project.builder().id(3L).name("p").build();
        ScanResult scanResult = mock(ScanResult.class);
        when(scanResult.getId()).thenReturn(10L);
        when(scanResult.getStatus()).thenReturn(ScanStatus.COMPLETED);
        when(scanResult.getProject()).thenReturn(project);
        when(scanResultRepository.findById(10L)).thenReturn(java.util.Optional.of(scanResult));
        when(scanComponentRepository.countByScanResultId(10L)).thenReturn(5L);
        doNothing().when(projectAccessService).assertCanViewProject(3L);

        var response = scanController.scanStatus(10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        ScanStatusResponse body = response.getBody();
        assertThat(body.getScanId()).isEqualTo(10L);
        assertThat(body.getStatus()).isEqualTo("COMPLETED");
        assertThat(body.getComponentCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("scanStatus: 다른 프로젝트 스캔은 403 ForbiddenException")
    void scanStatus_otherProject_throws403() {
        Project project = Project.builder().id(99L).name("other").build();
        ScanResult scanResult = mock(ScanResult.class);
        when(scanResult.getProject()).thenReturn(project);
        when(scanResultRepository.findById(10L)).thenReturn(java.util.Optional.of(scanResult));
        doThrow(new ForbiddenException("denied")).when(projectAccessService).assertCanViewProject(99L);

        assertThatThrownBy(() -> scanController.scanStatus(10L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("scanStatus: 존재하지 않는 scanId는 404를 반환한다")
    void scanStatus_notFound_returns404() {
        when(scanResultRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        var response = scanController.scanStatus(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
