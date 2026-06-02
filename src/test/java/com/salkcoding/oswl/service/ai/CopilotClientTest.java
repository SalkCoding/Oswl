package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("CopilotClient 단위 테스트")
class CopilotClientTest {

    private CopilotClient client;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        AiPromptTemplateService prompts = new AiPromptTemplateService(
                new DefaultResourceLoader(), "classpath:ai/prompts.properties");
        prompts.reloadWithLocale("en");
        client = new CopilotClient(prompts, new AiCallTrace(new AiDebugSettings()));
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    private AiSetting setting(String apiKey, String model) {
        return AiSetting.builder()
                .provider(AiProvider.COPILOT)
                .apiKey(apiKey)
                .modelName(model)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(String content) {
        Map<String, Object> message = Map.of("content", content);
        Map<String, Object> choice  = Map.of("message", message);
        Map<String, Object> body    = Map.of("choices", List.of(choice));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    // ── callWithSetting ───────────────────────────────────────────────────

    @Test
    @DisplayName("callWithSetting: API 키(GitHub token)가 없으면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_noApiKey_returnsNull() {
        String result = client.callWithSetting("prompt", setting(null, null));

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("callWithSetting: 정상 응답에서 choices[0].message.content를 반환한다")
    void callWithSetting_success_returnsContent() {
        stubResponse("CVE analysis done.");
        AiSetting s = setting("ghp_github_token", "gpt-4o");

        String result = client.callWithSetting("prompt", s);

        assertThat(result).isEqualTo("CVE analysis done.");
    }

    @Test
    @DisplayName("callWithSetting: RestTemplate이 예외를 던지면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_exception_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("network error"));

        String result = client.callWithSetting("prompt", setting("token", null));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("callWithSetting: choices가 비어있으면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_emptyChoices_returnsNull() {
        Map<String, Object> body = Map.of("choices", List.of());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(client.callWithSetting("prompt", setting("token", null))).isNull();
    }

    @Test
    @DisplayName("callWithSetting: body가 null이면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_nullBody_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.callWithSetting("prompt", setting("token", null))).isNull();
    }

    @Test
    @DisplayName("callWithSetting: setting이 null이어도 예외 없이 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_nullSetting_returnsNull() {
        String result = client.callWithSetting("prompt", null);

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    // ── prompt builders ───────────────────────────────────────────────────

    @Test
    @DisplayName("summarizeCve: no apiKey configured → returns null without HTTP call")
    @SuppressWarnings("unchecked")
    void summarizeCve_noSetting_returnsNull() {
        // summarizeCve calls call(prompt, null) — no apiKey → returns null immediately
        String result = client.summarizeCve("CVE-2021-44228", "CRITICAL", 10.0, "RCE", "log4j");

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("summarizeLicenseRisk: no apiKey configured → returns null without HTTP call")
    @SuppressWarnings("unchecked")
    void summarizeLicenseRisk_noSetting_returnsNull() {
        String result = client.summarizeLicenseRisk("LGPL-2.1", "CAUTION", "parser");

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }
}
