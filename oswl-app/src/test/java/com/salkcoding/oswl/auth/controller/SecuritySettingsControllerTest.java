package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.MailTestRequest;
import com.salkcoding.oswl.auth.dto.SecuritySettingResponse;
import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuritySettingsController unit tests")
class SecuritySettingsControllerTest {

    @Mock SecuritySettingService securitySettingService;

    @InjectMocks SecuritySettingsController controller;

    // -- GET --

    @Test
    @DisplayName("GET: returns 200 with setting response")
    void get_returns200() {
        SecuritySetting entity = new SecuritySetting();
        SecuritySettingResponse resp = mock(SecuritySettingResponse.class);
        when(securitySettingService.getOrCreate()).thenReturn(entity);
        when(securitySettingService.toResponse(entity)).thenReturn(resp);

        ResponseEntity<SecuritySettingResponse> result = controller.get();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(resp);
    }

    // -- PUT --

    @Test
    @DisplayName("PUT: returns 200 with updated response")
    void update_returns200() {
        SecuritySetting entity = new SecuritySetting();
        SecuritySettingResponse resp = mock(SecuritySettingResponse.class);
        SecuritySettingUpdateRequest req = mock(SecuritySettingUpdateRequest.class);
        when(securitySettingService.update(req)).thenReturn(entity);
        when(securitySettingService.toResponse(entity)).thenReturn(resp);

        ResponseEntity<SecuritySettingResponse> result = controller.update(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(resp);
    }

    // -- POST /mail/test --

    @Test
    @DisplayName("testMail: success returns 200 with 'Connection successful.' message")
    void testMail_success_returns200() throws Exception {
        MailTestRequest req = mock(MailTestRequest.class);
        doNothing().when(securitySettingService).testMailConnection(req);

        ResponseEntity<Map<String, Object>> result = controller.testMail(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsEntry("message", "Connection successful.");
    }

    @Test
    @DisplayName("testMail: MessagingException returns 400 with error message")
    void testMail_messagingException_returns400() throws Exception {
        MailTestRequest req = mock(MailTestRequest.class);
        doThrow(new MessagingException("Connection refused")).when(securitySettingService).testMailConnection(req);

        ResponseEntity<Map<String, Object>> result = controller.testMail(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody()).containsKey("message");
        assertThat((String) result.getBody().get("message")).contains("Connection refused");
    }

    @Test
    @DisplayName("testMail: MessagingException with null message falls back to default text")
    void testMail_messagingException_nullMessage_fallsBack() throws Exception {
        MailTestRequest req = mock(MailTestRequest.class);
        doThrow(new MessagingException(null)).when(securitySettingService).testMailConnection(req);

        ResponseEntity<Map<String, Object>> result = controller.testMail(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) result.getBody().get("message")).isEqualTo("SMTP connection failed.");
    }

    @Test
    @DisplayName("testMail: RuntimeException returns 500 with error message")
    void testMail_unexpectedException_returns500() throws Exception {
        MailTestRequest req = mock(MailTestRequest.class);
        doThrow(new RuntimeException("Unexpected failure")).when(securitySettingService).testMailConnection(req);

        ResponseEntity<Map<String, Object>> result = controller.testMail(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat((String) result.getBody().get("message")).contains("Unexpected failure");
    }

    @Test
    @DisplayName("testMail: RuntimeException with null message falls back to default text")
    void testMail_unexpectedException_nullMessage_fallsBack() throws Exception {
        MailTestRequest req = mock(MailTestRequest.class);
        doThrow(new RuntimeException((String) null)).when(securitySettingService).testMailConnection(req);

        ResponseEntity<Map<String, Object>> result = controller.testMail(req);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat((String) result.getBody().get("message")).isEqualTo("Unexpected error.");
    }
}
