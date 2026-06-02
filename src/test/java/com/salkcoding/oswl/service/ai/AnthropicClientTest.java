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

@DisplayName("AnthropicClient 단위 테스트")
class AnthropicClientTest {

    private AnthropicClient client;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        AiPromptTemplateService prompts = new AiPromptTemplateService(
                new DefaultResourceLoader(), "classpath:ai/prompts.properties");
        prompts.reloadWithLocale("en");
        client = new AnthropicClient(prompts, new AiCallTrace(new AiDebugSettings()));
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    private AiSetting setting(String apiKey, String model) {
        return AiSetting.builder()
                .provider(AiProvider.ANTHROPIC)
                .apiKey(apiKey)
                .modelName(model)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(String text) {
        Map<String, Object> contentItem = Map.of("type", "text", "text", text);
        Map<String, Object> body        = Map.of("content", List.of(contentItem));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    // ── callWithSetting ───────────────────────────────────────────────────

    @Test
    @DisplayName("callWithSetting: API 키가 없으면 null을 반환하고 HTTP를 호출하지 않는다")
    @SuppressWarnings("unchecked")
    void callWithSetting_noApiKey_returnsNullWithoutHttpCall() {
        String result = client.callWithSetting("prompt", setting(null, null));

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("callWithSetting: 빈 API 키도 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_blankApiKey_returnsNull() {
        String result = client.callWithSetting("prompt", setting("  ", null));

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("callWithSetting: 정상 응답에서 content[0].text를 반환한다")
    void callWithSetting_successfulResponse_returnsText() {
        stubResponse("Analysis complete.");
        AiSetting s = setting("sk-ant-key", "claude-3-5-sonnet-20241022");

        String result = client.callWithSetting("prompt", s, "completion", "sk-ant-key");

        assertThat(result).isEqualTo("Analysis complete.");
    }

    @Test
    @DisplayName("callWithSetting: RestTemplate이 예외를 던지면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_exception_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("timeout"));

        String result = client.callWithSetting("prompt", setting("sk-key", null));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("callWithSetting: content 배열이 비어있으면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_emptyContent_returnsNull() {
        Map<String, Object> body = Map.of("content", List.of());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(client.callWithSetting("prompt", setting("key", null))).isNull();
    }

    @Test
    @DisplayName("callWithSetting: body가 null이면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_nullBody_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.callWithSetting("prompt", setting("key", null))).isNull();
    }

    // ── prompt builders ───────────────────────────────────────────────────

    @Test
    @DisplayName("summarizeCve: no apiKey configured → returns null without HTTP call")
    @SuppressWarnings("unchecked")
    void summarizeCve_noSetting_returnsNull() {
        // summarizeCve calls call(prompt, null) — no apiKey → returns null immediately
        String result = client.summarizeCve("CVE-2023-1234", "HIGH", 8.5, "SQLI", "myapp");

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("summarizeLicenseRisk: no apiKey configured → returns null without HTTP call")
    @SuppressWarnings("unchecked")
    void summarizeLicenseRisk_noSetting_returnsNull() {
        String result = client.summarizeLicenseRisk("GPL-3.0", "RESTRICTED", "core");

        assertThat(result).isNull();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class));
    }
}
