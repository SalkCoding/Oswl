package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.AiPreferences;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.AiTestConnectionRequest;
import com.salkcoding.oswl.repository.AiSettingRepository;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiSettingController 단위 테스트")
class AiSettingControllerTest {

    @Mock AiSettingRepository aiSettingRepository;
    @Mock AuditLogService     auditLogService;
    @Mock EncryptionService   encryptionService;
    @Mock AiAnalysisService   aiAnalysisService;
    @Mock AiPreferencesService aiPreferencesService;
    @Mock OutboundUrlValidator outboundUrlValidator;

    @InjectMocks AiSettingController controller;

    private AiPreferences defaultPrefs() {
        return AiPreferences.defaults("en", 10, 8, "CRITICAL,HIGH", 0);
    }

    private void stubPreferences() {
        lenient().when(aiPreferencesService.getEffective()).thenReturn(defaultPrefs());
    }

    @BeforeEach
    void setUpPreferences() {
        stubPreferences();
    }

    // ── getCurrent ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getCurrent: 활성 설정 없음 → 빈 메시지 응답")
    void getCurrent_noActive_returnsEmptyMessage() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        ResponseEntity<AiSettingResponse> resp = controller.getCurrent();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("No AI provider configured");
        assertThat(resp.getBody().getPromptsLocale()).isEqualTo("en");
        assertThat(resp.getBody().getCveLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("getCurrent: 활성 설정 있음 → provider, modelName 반환")
    void getCurrent_hasActive_returnsSettings() {
        AiSetting setting = AiSetting.builder()
                .provider(AiProvider.OPENAI)
                .modelName("gpt-4o")
                .apiKey("sk-12345678abcdefgh")
                .build();
        setting.activate();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));

        ResponseEntity<AiSettingResponse> resp = controller.getCurrent();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getProvider()).isEqualTo(AiProvider.OPENAI);
        assertThat(resp.getBody().getModelName()).isEqualTo("gpt-4o");
        assertThat(resp.getBody().getActive()).isTrue();
        // Key must be masked
        assertThat(resp.getBody().getApiKey()).doesNotContain("12345678abcdefgh");
    }

    @Test
    @DisplayName("getCurrent: apiKey 짧음(<=8자) → '***' 마스킹")
    void getCurrent_shortApiKey_masksToThreeStars() {
        AiSetting setting = AiSetting.builder()
                .provider(AiProvider.LOCAL)
                .apiKey("short")
                .build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));

        ResponseEntity<AiSettingResponse> resp = controller.getCurrent();

        assertThat(resp.getBody().getApiKey()).isEqualTo("***");
    }

    // ── upsert ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("upsert: 새 provider → AiSetting 생성 후 저장")
    void upsert_newProvider_createsAndSaves() {
        AiSettingUpdateRequest req = new AiSettingUpdateRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey("sk-test-key");
        req.setModelName("gpt-4o");
        req.setActivate(false);

        when(aiSettingRepository.findByProvider(AiProvider.OPENAI)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-test-key")).thenReturn("enc-sk-test-key");
        when(aiSettingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<AiSettingResponse> resp = controller.upsert(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(aiSettingRepository).save(any());
        verify(auditLogService).log(eq("AI_SETTING.SAVE"), eq("AI_SETTING"), any(), any(), any());
    }

    @Test
    @DisplayName("upsert: apiKey null → 암호화 안 함, null 저장")
    void upsert_nullApiKey_skipsEncryption() {
        AiSettingUpdateRequest req = new AiSettingUpdateRequest();
        req.setProvider(AiProvider.LOCAL);
        req.setApiKey(null);
        req.setActivate(false);

        when(aiSettingRepository.findByProvider(AiProvider.LOCAL)).thenReturn(Optional.empty());
        when(aiSettingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        controller.upsert(req);

        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    @DisplayName("upsert: apiKey blank → 암호화 안 함")
    void upsert_blankApiKey_skipsEncryption() {
        AiSettingUpdateRequest req = new AiSettingUpdateRequest();
        req.setProvider(AiProvider.LOCAL);
        req.setApiKey("   ");
        req.setActivate(false);

        when(aiSettingRepository.findByProvider(AiProvider.LOCAL)).thenReturn(Optional.empty());
        when(aiSettingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        controller.upsert(req);

        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    @DisplayName("upsert: activate=true → 기존 활성 비활성화 + 새 설정 활성화")
    void upsert_withActivate_deactivatesOldAndActivatesNew() {
        AiSetting existingActive = AiSetting.builder().provider(AiProvider.ANTHROPIC).build();
        existingActive.activate();

        AiSettingUpdateRequest req = new AiSettingUpdateRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey("sk-new-key");
        req.setActivate(true);

        when(aiSettingRepository.findByProvider(AiProvider.OPENAI)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-new-key")).thenReturn("enc-sk-new-key");
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(existingActive));
        when(aiSettingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        controller.upsert(req);

        assertThat(existingActive.isActive()).isFalse();
    }

    @Test
    @DisplayName("upsert: 기존 provider → 기존 AiSetting 업데이트")
    void upsert_existingProvider_updatesExistingSetting() {
        AiSetting existing = AiSetting.builder().provider(AiProvider.GEMINI).modelName("gemini-1").build();
        AiSettingUpdateRequest req = new AiSettingUpdateRequest();
        req.setProvider(AiProvider.GEMINI);
        req.setApiKey("new-key");
        req.setModelName("gemini-2");
        req.setActivate(false);

        when(aiSettingRepository.findByProvider(AiProvider.GEMINI)).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("new-key")).thenReturn("enc-new-key");
        when(aiSettingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<AiSettingResponse> resp = controller.upsert(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getModelName()).isEqualTo("gemini-2");
    }

    // ── deactivate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate: 활성 설정 없음 → noContent, 감사 로그 없음")
    void deactivate_noActive_returnsNoContent() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        ResponseEntity<Void> resp = controller.deactivate(null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("deactivate: 활성 설정 있음 → 비활성화 + 감사 로그")
    void deactivate_hasActive_deactivatesAndLogs() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).build();
        setting.activate();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));

        ResponseEntity<Void> resp = controller.deactivate(null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setting.isActive()).isFalse();
        verify(auditLogService).log(eq("AI_SETTING.DEACTIVATE"), eq("AI_SETTING"), any(), any(), isNull());
    }

    // ── activate ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("activate: provider 설정 없음 → IAE")
    void activate_notFound_throwsIAE() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(aiSettingRepository.findByProvider(AiProvider.ANTHROPIC)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.activate(AiProvider.ANTHROPIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("activate: provider 설정 있음 → 활성화 후 응답")
    void activate_found_activatesAndReturns() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.GEMINI).modelName("gemini-pro").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(aiSettingRepository.findByProvider(AiProvider.GEMINI)).thenReturn(Optional.of(setting));

        ResponseEntity<AiSettingResponse> resp = controller.activate(AiProvider.GEMINI);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setting.isActive()).isTrue();
        verify(auditLogService).log(eq("AI_SETTING.ACTIVATE"), eq("AI_SETTING"), any(), any(), any());
    }

    @Test
    @DisplayName("activate: 기존 활성 설정이 있으면 비활성화 후 새 설정 활성화")
    void activate_existingActive_deactivatesFirst() {
        AiSetting oldActive = AiSetting.builder().provider(AiProvider.OPENAI).build();
        oldActive.activate();
        AiSetting newSetting = AiSetting.builder().provider(AiProvider.ANTHROPIC).build();

        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(oldActive));
        when(aiSettingRepository.findByProvider(AiProvider.ANTHROPIC)).thenReturn(Optional.of(newSetting));

        controller.activate(AiProvider.ANTHROPIC);

        assertThat(oldActive.isActive()).isFalse();
        assertThat(newSetting.isActive()).isTrue();
    }

    // ── testConnection ────────────────────────────────────────────────────

    @Test
    @DisplayName("testConnection: 직접 apiKey 사용 → 연결 성공")
    void testConnection_withPlainApiKey_success() {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey("sk-direct-key");
        req.setModelName("gpt-4o");

        when(aiAnalysisService.testConnection(any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("testConnection: apiKey 없고 저장된 key도 없음 → 400")
    void testConnection_noApiKey_noStoredKey_returns400() {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey("");

        when(aiSettingRepository.findByProvider(AiProvider.OPENAI)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("testConnection: 저장된 key 복호화 사용 → 연결 실패 → success=false")
    void testConnection_storedKey_decrypts_connectionFails() throws Exception {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.ANTHROPIC);
        req.setApiKey(null);

        AiSetting stored = AiSetting.builder()
                .provider(AiProvider.ANTHROPIC)
                .apiKey("enc-stored-key")
                .build();
        when(aiSettingRepository.findByProvider(AiProvider.ANTHROPIC)).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-stored-key")).thenReturn("decrypted-key");
        when(aiAnalysisService.testConnection(any())).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("testConnection: LOCAL provider, apiKey blank → resolvedKey=null, 연결 성공")
    void testConnection_localProvider_noApiKey_success() {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.LOCAL);
        req.setApiKey("");
        req.setModelName("llama3");
        req.setBaseUrl("https://llm.example.com/v1");

        when(aiAnalysisService.testConnection(any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("testConnection: LOCAL provider는 localhost baseUrl 허용")
    void testConnection_localProvider_allowsLocalhost() {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.LOCAL);
        req.setApiKey("");
        req.setModelName("gemma4:e4b");
        req.setBaseUrl("http://localhost:11434/v1");

        when(aiAnalysisService.testConnection(any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        verify(outboundUrlValidator).validateLocalAiBaseUrl("http://localhost:11434/v1");
        verify(outboundUrlValidator, never()).validateHttpUrl(anyString());
    }

    @Test
    @DisplayName("testConnection: 비-LOCAL provider의 사설 baseUrl 차단")
    void testConnection_nonLocalPrivateBaseUrl_returnsFailureMessage() {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey("sk-test");
        req.setModelName("gpt-4o-mini");
        req.setBaseUrl("http://127.0.0.1:11434/v1");

        doThrow(new OutboundUrlBlockedException("Loopback addresses are not allowed."))
                .when(outboundUrlValidator).validateHttpUrl("http://127.0.0.1:11434/v1");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        assertThat(resp.getBody().get("message").toString()).contains("Loopback");
        verify(aiAnalysisService, never()).testConnection(any());
    }

    @Test
    @DisplayName("testConnection: 저장된 key decrypt 실패 → 400, 연결 시도 없음")
    void testConnection_decryptFails_returnsBadRequest() throws Exception {
        AiTestConnectionRequest req = new AiTestConnectionRequest();
        req.setProvider(AiProvider.OPENAI);
        req.setApiKey(null);

        AiSetting stored = AiSetting.builder()
                .provider(AiProvider.OPENAI)
                .apiKey("ciphertext-not-decryptable")
                .build();
        when(aiSettingRepository.findByProvider(AiProvider.OPENAI)).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("ciphertext-not-decryptable")).thenThrow(new RuntimeException("not encrypted"));

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        verify(aiAnalysisService, never()).testConnection(any());
    }
}
