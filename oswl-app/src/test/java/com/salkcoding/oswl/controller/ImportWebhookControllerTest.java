package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.service.ImportWebhookService;
import com.salkcoding.oswl.testing.TestTags;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("ImportWebhookController")
class ImportWebhookControllerTest {

    @Mock ImportWebhookService importWebhookService;
    @InjectMocks ImportWebhookController controller;

    @Test
    @DisplayName("accepted push returns 200 with jobId")
    void accepted_returnsOk() {
        when(importWebhookService.handlePush(anyString(), anyMap()))
                .thenReturn(new ImportWebhookService.WebhookResult(true, "queued", "job-1"));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

        ResponseEntity<Map<String, Object>> resp = controller.handleWebhook("{}", request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accepted", true).containsEntry("jobId", "job-1");
    }

    @Test
    @DisplayName("rejected webhook returns 400")
    void rejected_returnsBadRequest() {
        when(importWebhookService.handlePush(anyString(), anyMap()))
                .thenReturn(new ImportWebhookService.WebhookResult(false, "bad sig", null));

        ResponseEntity<Map<String, Object>> resp = controller.handleWebhook("{}", mock(HttpServletRequest.class));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("accepted", false);
    }
}
