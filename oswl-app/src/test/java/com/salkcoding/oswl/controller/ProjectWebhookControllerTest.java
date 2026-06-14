package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.ProjectWebhookConfigDto;
import com.salkcoding.oswl.dto.UpdateProjectWebhookRequest;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectWebhookService;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectWebhookController")
class ProjectWebhookControllerTest {

    @Mock ProjectWebhookService projectWebhookService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks ProjectWebhookController controller;

    @Test
    @DisplayName("get returns webhook config")
    void get_returnsConfig() {
        ProjectWebhookConfigDto dto = ProjectWebhookConfigDto.builder()
                .enabled(true).webhookUrl("https://example.com/hook").build();
        when(projectWebhookService.getConfig(1L)).thenReturn(dto);

        ResponseEntity<ProjectWebhookConfigDto> resp = controller.get(1L);

        verify(projectAccessService).assertCanViewProject(1L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("update delegates to service")
    void update_delegates() {
        UpdateProjectWebhookRequest req = new UpdateProjectWebhookRequest();
        req.setEnabled(true);
        ProjectWebhookConfigDto dto = ProjectWebhookConfigDto.builder().enabled(true).build();
        when(projectWebhookService.updateConfig(1L, req)).thenReturn(dto);

        ResponseEntity<ProjectWebhookConfigDto> resp = controller.update(1L, req);

        assertThat(resp.getBody().isEnabled()).isTrue();
    }
}
