package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.VcsConnectionService;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportRequest;
import com.salkcoding.oswl.service.QuickImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickImportController unit tests")
class QuickImportControllerTest {

    @Mock QuickImportService    quickImportService;
    @Mock VcsConnectionService  vcsConnectionService;

    @InjectMocks QuickImportController controller;

    private OswlUserPrincipal principal(long id) {
        return new OswlUserPrincipal(id, "user@test.com", "hash", "Test", false, true,
                List.of(), Set.of(), Set.of(Permission.PROJECT_VIEW), false);
    }

    // -- GET /projects/quick-import --

    @Test
    @DisplayName("quickImportPage: returns view name")
    void quickImportPage_returnsViewName() {
        String view = controller.quickImportPage();
        assertThat(view).isEqualTo("projects/quick-import");
    }

    // -- GET /api/quick-import/connections --

    @Test
    @DisplayName("connections: returns 200 with list of VCS connections")
    void connections_returns200() {
        OswlUserPrincipal p = principal(1L);
        when(vcsConnectionService.findByCurrentUser(1L)).thenReturn(List.of());

        ResponseEntity<?> result = controller.connections(p);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -- GET /api/quick-import/repos --

    @Test
    @DisplayName("listRepos: success returns 200 with list")
    void listRepos_success_returns200() {
        OswlUserPrincipal p = principal(1L);
        when(quickImportService.listRepos(VcsProvider.GITHUB, 1L)).thenReturn(List.of());

        ResponseEntity<?> result = controller.listRepos(VcsProvider.GITHUB, p);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("listRepos: exception returns 502 with error map")
    void listRepos_exception_returns502() {
        OswlUserPrincipal p = principal(1L);
        when(quickImportService.listRepos(VcsProvider.GITLAB, 1L))
                .thenThrow(new RuntimeException("VCS unreachable"));

        ResponseEntity<?> result = controller.listRepos(VcsProvider.GITLAB, p);

        assertThat(result.getStatusCode().value()).isEqualTo(502);
        assertThat(result.getBody()).isInstanceOf(Map.class);
        Object body = result.getBody();
        assertThat(body.toString()).contains("error");
    }

    // -- POST /api/quick-import/start --

    @Test
    @DisplayName("start: returns 200 with jobId")
    void start_returns200WithJobId() {
        OswlUserPrincipal p = principal(2L);
        QuickImportRequest req = mock(QuickImportRequest.class);
        when(req.getRepoUrl()).thenReturn("https://github.com/org/repo");
        when(req.getBranch()).thenReturn("main");
        when(quickImportService.startImport("https://github.com/org/repo", "main", 2L))
                .thenReturn("job-123");

        ResponseEntity<Map<String, String>> result = controller.start(req, p);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("jobId", "job-123");
    }

    // -- GET /api/quick-import/job/{jobId} --

    @Test
    @DisplayName("jobStatus: found returns 200 with status")
    void jobStatus_found_returns200() {
        QuickImportJobStatus status = mock(QuickImportJobStatus.class);
        when(quickImportService.getJobStatus("job-123")).thenReturn(status);

        ResponseEntity<QuickImportJobStatus> result = controller.jobStatus("job-123");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(status);
    }

    @Test
    @DisplayName("jobStatus: not found returns 404")
    void jobStatus_notFound_returns404() {
        when(quickImportService.getJobStatus("unknown-job")).thenReturn(null);

        ResponseEntity<QuickImportJobStatus> result = controller.jobStatus("unknown-job");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
