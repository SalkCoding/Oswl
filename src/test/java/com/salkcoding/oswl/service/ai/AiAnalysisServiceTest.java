package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiAnalysisServiceTest {

    @Mock AiSettingRepository aiSettingRepository;
    @Mock OpenAiClient openAiClient;
    @Mock AnthropicClient anthropicClient;

    @InjectMocks
    AiAnalysisService aiAnalysisService;

    // ── isAiConfigured ────────────────────────────────────────────────────

    @Test
    @DisplayName("활성화된 AI 설정이 존재하면 true를 반환한다")
    void isAiConfigured_returnsTrue_whenActiveSettingPresent() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).active(true).build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));

        assertThat(aiAnalysisService.isAiConfigured()).isTrue();
    }

    @Test
    @DisplayName("활성화된 AI 설정이 없으면 false를 반환한다")
    void isAiConfigured_returnsFalse_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.isAiConfigured()).isFalse();
    }

    // ── summarizeCve ──────────────────────────────────────────────────────

    @Test
    @DisplayName("활성 설정이 없으면 null을 반환한다")
    void summarizeCve_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.summarizeCve("CVE-X", "HIGH", 7.5, "XSS", "lib 1.0")).isNull();
        verifyNoInteractions(openAiClient, anthropicClient);
    }

    @Test
    @DisplayName("OPENAI 제공자는 OpenAiClient에 위임한다")
    void summarizeCve_delegatesToOpenAiClient_forOpenAiProvider() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("sk-test").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("RCE risk: critical");

        String result = aiAnalysisService.summarizeCve("CVE-2024-001", "CRITICAL", 9.8, "RCE", "lib 1.0");

        assertThat(result).isEqualTo("RCE risk: critical");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(anthropicClient);
    }

    @Test
    @DisplayName("LOCAL 제공자도 OpenAiClient에 위임한다")
    void summarizeCve_delegatesToOpenAiClient_forLocalProvider() {
        AiSetting setting = AiSetting.builder()
                .provider(AiProvider.LOCAL).baseUrl("http://localhost:11434/v1").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("local result");

        assertThat(aiAnalysisService.summarizeCve("CVE-L", "LOW", 2.0, "Info", "comp 1.0"))
                .isEqualTo("local result");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(anthropicClient);
    }

    @Test
    @DisplayName("GEMINI 제공자도 OpenAiClient에 위임한다")
    void summarizeCve_delegatesToOpenAiClient_forGeminiProvider() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.GEMINI).apiKey("gemini-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("gemini result");

        assertThat(aiAnalysisService.summarizeCve("CVE-G", "HIGH", 7.5, "Injection", "lib 1.0"))
                .isEqualTo("gemini result");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(anthropicClient);
    }

    @Test
    @DisplayName("ANTHROPIC 제공자는 AnthropicClient에 위임한다")
    void summarizeCve_delegatesToAnthropicClient_forAnthropicProvider() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("claude result");

        String result = aiAnalysisService.summarizeCve("CVE-2024-002", "HIGH", 8.0, "XSS", "react 18");

        assertThat(result).isEqualTo("claude result");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(openAiClient);
    }

    // ── summarizeLicenseRisk ──────────────────────────────────────────────

    @Test
    @DisplayName("라이선스 위험: 활성 설정이 없으면 null을 반환한다")
    void summarizeLicenseRisk_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.summarizeLicenseRisk("AGPL", "RESTRICTED", "mylib")).isNull();
    }

    @Test
    @DisplayName("라이선스 위험: OPENAI 설정 시 OpenAiClient에 위임한다")
    void summarizeLicenseRisk_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("License risk: AGPL must be disclosed");

        String result = aiAnalysisService.summarizeLicenseRisk("AGPL-3.0", "RESTRICTED", "mylib");

        assertThat(result).isEqualTo("License risk: AGPL must be disclosed");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("라이선스 위험: ANTHROPIC 설정 시 AnthropicClient에 위임한다")
    void summarizeLicenseRisk_delegatesToAnthropicClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("CC-BY-SA compliance risk");

        assertThat(aiAnalysisService.summarizeLicenseRisk("CC-BY-SA", "CAUTION", "lib"))
                .isEqualTo("CC-BY-SA compliance risk");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
    }

    // ── generateRiskInsight ───────────────────────────────────────────────

    @Test
    @DisplayName("리스크 인사이트: 활성 설정이 없으면 null을 반환한다")
    void generateRiskInsight_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.generateRiskInsight("MyProject", 5, 2, "1.0, 1.1")).isNull();
    }

    @Test
    @DisplayName("리스크 인사이트: OPENAI 설정 시 OpenAiClient에 위임한다")
    void generateRiskInsight_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("Risk is trending up");

        assertThat(aiAnalysisService.generateRiskInsight("MyProject", 5, 2, "1.0, 1.1, 1.2"))
                .isEqualTo("Risk is trending up");
    }

    @Test
    @DisplayName("리스크 인사이트: ANTHROPIC 설정 시 AnthropicClient에 위임한다")
    void generateRiskInsight_delegatesToAnthropicClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("Security improved");

        assertThat(aiAnalysisService.generateRiskInsight("P", -3, -1, "2.0, 3.0"))
                .isEqualTo("Security improved");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(openAiClient);
    }

    @Test
    @DisplayName("보안 이슈가 감소했을 때도 올바른 프롬프트로 위임한다")
    void generateRiskInsight_handlesNegativeDelta() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("Improvement detected");

        // securityDelta=-5 (감소), licenseDelta=2 (증가) - 예외 없이 위임 확인
        String result = aiAnalysisService.generateRiskInsight("SecureApp", -5, 2, "1.0, 2.0");

        assertThat(result).isEqualTo("Improvement detected");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }
}
