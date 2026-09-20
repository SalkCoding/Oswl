package com.salkcoding.oswl.controller.snapshot;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SnapshotAdminController unit tests")
class SnapshotAdminControllerTest {

    @Mock AirgappedSnapshotService snapshotService;
    @Mock KevCatalogService kevCatalogService;
    @Mock AuditLogService auditLogService;

    @InjectMocks SnapshotAdminController controller;

    @Test
    @DisplayName("export forwards a restricted distribution profile")
    void export_forwardsRestrictedDistributionProfile() {
        byte[] bundle = {1, 2, 3};
        when(snapshotService.exportBundle("github-attributed")).thenReturn(bundle);

        ResponseEntity<byte[]> response = controller.exportBundle("github-attributed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(bundle);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment; filename=");
        verify(snapshotService).exportBundle("github-attributed");
        verify(auditLogService).log(eq("SNAPSHOT.EXPORT"), eq("SNAPSHOT"), isNull(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("sizeBytes=3 distributionProfile=github-attributed"));
    }

    @Test
    @DisplayName("export defaults to the existing unreviewed service method")
    void export_defaultsToUnreviewed() {
        byte[] bundle = {4};
        when(snapshotService.exportBundle()).thenReturn(bundle);

        ResponseEntity<byte[]> response = controller.exportBundle("unreviewed");

        assertThat(response.getBody()).isSameAs(bundle);
        verify(snapshotService).exportBundle();
        verify(auditLogService).log(eq("SNAPSHOT.EXPORT"), eq("SNAPSHOT"), isNull(),
                org.mockito.ArgumentMatchers.anyString(),
                eq("sizeBytes=1 distributionProfile=unreviewed"));
    }

    @Test
    @DisplayName("direct no-arg callers retain the unreviewed export")
    void noArgExport_retainsCompatibility() {
        byte[] bundle = {5};
        when(snapshotService.exportBundle()).thenReturn(bundle);

        assertThat(controller.exportBundle().getBody()).isSameAs(bundle);
        verify(snapshotService).exportBundle();
    }

    @Test
    @DisplayName("GET export binds distributionProfile and forwards the restricted profile")
    void getExport_bindsProfileAndForwardsRestrictedProfile() throws Exception {
        byte[] bundle = {6, 7};
        when(snapshotService.exportBundle("github-attributed")).thenReturn(bundle);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/api/admin/snapshot/export")
                        .param("distributionProfile", "github-attributed"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bundle))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=")));

        verify(snapshotService).exportBundle("github-attributed");
    }
}
