package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock CopilotClient copilotClient;
    @Mock EncryptionService encryptionService;

    @InjectMocks
    AiAnalysisService aiAnalysisService;

    @BeforeEach
    void injectPromptTemplates() {
        AiPromptTemplateService prompts = new AiPromptTemplateService(
                new DefaultResourceLoader(), "classpath:ai/prompts.properties");
        prompts.reloadWithLocale("en");
        prompts.load();
        ReflectionTestUtils.setField(aiAnalysisService, "promptTemplates", prompts);
    }

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

        assertThat(aiAnalysisService.summarizeCve("CVE-X", "HIGH", 7.5, "XSS")).isNull();
        verifyNoInteractions(openAiClient, anthropicClient);
    }

    @Test
    @DisplayName("OPENAI 제공자는 OpenAiClient에 위임한다")
    void summarizeCve_delegatesToOpenAiClient_forOpenAiProvider() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("sk-test").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("RCE risk: critical");

        String result = aiAnalysisService.summarizeCve("CVE-2024-001", "CRITICAL", 9.8, "RCE");

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

        assertThat(aiAnalysisService.summarizeCve("CVE-L", "LOW", 2.0, "Info"))
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

        assertThat(aiAnalysisService.summarizeCve("CVE-G", "HIGH", 7.5, "Injection"))
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

        String result = aiAnalysisService.summarizeCve("CVE-2024-002", "HIGH", 8.0, "XSS");

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

    // ── summarizeCve COPILOT ───────────────────────────────────────────────

    @Test
    @DisplayName("COPILOT 제공자는 CopilotClient에 위임한다")
    void summarizeCve_delegatesToCopilotClient_forCopilotProvider() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.COPILOT).build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(copilotClient.callWithSetting(anyString(), eq(setting))).thenReturn("copilot result");

        String result = aiAnalysisService.summarizeCve("CVE-C", "HIGH", 7.0, "comp");

        assertThat(result).isEqualTo("copilot result");
        verify(copilotClient).callWithSetting(anyString(), eq(setting));
        verifyNoInteractions(openAiClient, anthropicClient);
    }

    // ── generateSecurityTrendInsight ──────────────────────────────────────

    @Test
    @DisplayName("보안 트렌드: 활성 설정이 없으면 null을 반환한다")
    void generateSecurityTrendInsight_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.generateSecurityTrendInsight("P", 3, "1.0, 2.0", "-")).isNull();
    }

    @Test
    @DisplayName("보안 트렌드: OPENAI 설정 시 OpenAiClient에 위임한다")
    void generateSecurityTrendInsight_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("Security trend result");

        assertThat(aiAnalysisService.generateSecurityTrendInsight("P", 5, "v1, v2", "-"))
                .isEqualTo("Security trend result");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("보안 트렌드: ANTHROPIC 설정 시 AnthropicClient에 위임한다")
    void generateSecurityTrendInsight_delegatesToAnthropicClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("Anthropic trend");

        assertThat(aiAnalysisService.generateSecurityTrendInsight("P", -2, "v3, v4", "-"))
                .isEqualTo("Anthropic trend");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
    }

    // ── generateLicenseTrendInsight ───────────────────────────────────────

    @Test
    @DisplayName("라이선스 트렌드: 활성 설정이 없으면 null을 반환한다")
    void generateLicenseTrendInsight_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.generateLicenseTrendInsight("P", 1, "v1, v2", "-")).isNull();
    }

    @Test
    @DisplayName("라이선스 트렌드: OPENAI 설정 시 OpenAiClient에 위임한다")
    void generateLicenseTrendInsight_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("License trend result");

        assertThat(aiAnalysisService.generateLicenseTrendInsight("P", 2, "v1, v2", "-"))
                .isEqualTo("License trend result");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    // ── summarizeSecurityPosture ──────────────────────────────────────────

    private static AiEnrichmentContextBuilder.PostureContext samplePosture() {
        return new AiEnrichmentContextBuilder.PostureContext(2, 5, 1, 0, 100, 8, 2, 3, "- test issue");
    }

    @Test
    @DisplayName("보안 포스처: 활성 설정이 없으면 null을 반환한다")
    void summarizeSecurityPosture_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.summarizeSecurityPosture("P", samplePosture())).isNull();
    }

    @Test
    @DisplayName("보안 포스처: OPENAI 설정 시 OpenAiClient에 위임한다")
    void summarizeSecurityPosture_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("Posture summary");

        assertThat(aiAnalysisService.summarizeSecurityPosture("MyProject", samplePosture()))
                .isEqualTo("Posture summary");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("보안 포스처: ANTHROPIC 설정 시 AnthropicClient에 위임한다")
    void summarizeSecurityPosture_delegatesToAnthropicClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("Claude posture");

        assertThat(aiAnalysisService.summarizeSecurityPosture("P", samplePosture()))
                .isEqualTo("Claude posture");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
    }

    // ── summarizeVersionDiff ──────────────────────────────────────────────

    @Test
    @DisplayName("버전 비교: 활성 설정이 없으면 null을 반환한다")
    void summarizeVersionDiff_returnsNull_whenNoActiveSetting() {
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        assertThat(aiAnalysisService.summarizeVersionDiff("P", "1.0", "2.0", 5, 2, 10, 3, "-")).isNull();
    }

    @Test
    @DisplayName("버전 비교: OPENAI 설정 시 OpenAiClient에 위임한다")
    void summarizeVersionDiff_delegatesToOpenAiClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("Version diff summary");

        assertThat(aiAnalysisService.summarizeVersionDiff("MyApp", "v1.0", "v2.0", 5, 2, 10, 3, "threats"))
                .isEqualTo("Version diff summary");
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("버전 비교: ANTHROPIC 설정 시 AnthropicClient에 위임한다")
    void summarizeVersionDiff_delegatesToAnthropicClient() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(setting));
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("Claude diff");

        assertThat(aiAnalysisService.summarizeVersionDiff("P", "1.0", "1.1", 1, 0, 3, 1, "-"))
                .isEqualTo("Claude diff");
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
    }

    // ── testConnection ────────────────────────────────────────────────────

    @Test
    @DisplayName("testConnection: OPENAI 제공자가 응답하면 true를 반환한다")
    void testConnection_openai_returnsTrue_whenResponseNotNull() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(openAiClient.callWithSetting(anyString(), eq(setting))).thenReturn("OK");

        assertThat(aiAnalysisService.testConnection(setting)).isTrue();
        verify(openAiClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("testConnection: 클라이언트가 예외를 던지면 false를 반환한다")
    void testConnection_returnsFalse_whenClientThrows() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.OPENAI).apiKey("key").build();
        when(openAiClient.callWithSetting(anyString(), eq(setting)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThat(aiAnalysisService.testConnection(setting)).isFalse();
    }

    @Test
    @DisplayName("testConnection: ANTHROPIC 제공자가 응답하면 true를 반환한다")
    void testConnection_anthropic_returnsTrue() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.ANTHROPIC).apiKey("ant-key").build();
        when(anthropicClient.callWithSetting(anyString(), eq(setting))).thenReturn("OK");

        assertThat(aiAnalysisService.testConnection(setting)).isTrue();
        verify(anthropicClient).callWithSetting(anyString(), eq(setting));
    }

    @Test
    @DisplayName("testConnection: COPILOT 제공자가 응답하면 true를 반환한다")
    void testConnection_copilot_returnsTrue() {
        AiSetting setting = AiSetting.builder().provider(AiProvider.COPILOT).build();
        when(copilotClient.callWithSetting(anyString(), eq(setting))).thenReturn("OK");

        assertThat(aiAnalysisService.testConnection(setting)).isTrue();
        verify(copilotClient).callWithSetting(anyString(), eq(setting));
    }}
