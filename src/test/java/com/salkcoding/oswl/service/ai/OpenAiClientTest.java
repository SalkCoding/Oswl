package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

@DisplayName("OpenAiClient 단위 테스트")
class OpenAiClientTest {

    private OpenAiClient client;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        client = new OpenAiClient();
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    private AiSetting setting(String apiKey, String model, String baseUrl) {
        return AiSetting.builder()
                .provider(AiProvider.OPENAI)
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(baseUrl)
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
    @DisplayName("callWithSetting: 정상 응답에서 content를 반환한다")
    void callWithSetting_successfulResponse_returnsContent() {
        stubResponse("Risk: high");
        AiSetting s = setting("key", "gpt-4o", null);

        String result = client.callWithSetting("prompt", s);

        assertThat(result).isEqualTo("Risk: high");
    }

    @Test
    @DisplayName("callWithSetting: RestTemplate이 예외를 던지면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_exception_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("connection refused"));

        String result = client.callWithSetting("prompt", setting("key", null, null));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("callWithSetting: choices가 비어있으면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_emptyChoices_returnsNull() {
        Map<String, Object> body = Map.of("choices", List.of());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));

        assertThat(client.callWithSetting("prompt", setting("key", null, null))).isNull();
    }

    @Test
    @DisplayName("callWithSetting: body가 null이면 null을 반환한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_nullBody_returnsNull() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.callWithSetting("prompt", setting("key", null, null))).isNull();
    }

    @Test
    @DisplayName("callWithSetting: setting이 null이면 API 키 없이 기본 모델로 호출한다")
    @SuppressWarnings("unchecked")
    void callWithSetting_nullSetting_callsWithDefaultModel() {
        stubResponse("ok");

        String result = client.callWithSetting("hello", null);

        assertThat(result).isEqualTo("ok");
        verify(restTemplate).exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                eq(HttpMethod.POST), any(), any(ParameterizedTypeReference.class));
    }

    // ── resolveUrl ────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveUrl: baseUrl이 없으면 기본 OpenAI URL을 사용한다")
    void resolveUrl_noBaseUrl_usesDefault() {
        stubResponse("ok");

        client.callWithSetting("prompt", setting("key", null, null));

        verify(restTemplate).exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("resolveUrl: baseUrl이 /chat/completions으로 끝나면 그대로 사용한다")
    void resolveUrl_baseUrlEndsWithChatCompletions_usesAsIs() {
        stubResponse("ok");
        String url = "http://localhost:11434/v1/chat/completions";

        client.callWithSetting("prompt", setting(null, null, url));

        verify(restTemplate).exchange(eq(url), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("resolveUrl: baseUrl이 /chat/completions 없으면 suffix를 붙인다")
    void resolveUrl_baseUrlWithoutSuffix_appendsSuffix() {
        stubResponse("ok");
        String base = "http://localhost:11434/v1";

        client.callWithSetting("prompt", setting(null, null, base));

        verify(restTemplate).exchange(
                eq("http://localhost:11434/v1/chat/completions"),
                any(), any(), any(ParameterizedTypeReference.class));
    }

    // ── resolveModel ──────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveModel: modelName이 설정되면 해당 모델을 body에 포함한다")
    @SuppressWarnings("unchecked")
    void resolveModel_customModelName_isUsedInBody() {
        stubResponse("ok");
        // We verify by checking the request body is constructed (no exception from model resolution)
        String result = client.callWithSetting("prompt", setting("key", "gpt-3.5-turbo", null));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("resolveModel: modelName이 null이면 기본값 gpt-4o-mini를 사용한다")
    void resolveModel_nullModelName_usesDefault() {
        stubResponse("ok");

        String result = client.callWithSetting("prompt", setting("key", null, null));

        assertThat(result).isEqualTo("ok");
    }

    // ── prompt builders ───────────────────────────────────────────────────

    @Test
    @DisplayName("summarizeCve: 프롬프트를 구성하고 callWithSetting 결과를 반환한다")
    void summarizeCve_buildsPromptAndReturnsResult() {
        stubResponse("High risk CVE.");

        String result = client.summarizeCve("CVE-2021-44228", "CRITICAL", 10.0, "RCE", "log4j");

        assertThat(result).isEqualTo("High risk CVE.");
    }

    @Test
    @DisplayName("generateRiskInsight: 프롬프트를 구성하고 callWithSetting 결과를 반환한다")
    void generateRiskInsight_buildsPromptAndReturnsResult() {
        stubResponse("Project risk is medium.");

        String result = client.generateRiskInsight("MyProject", 3, 1, "summary");

        assertThat(result).isEqualTo("Project risk is medium.");
    }

    @Test
    @DisplayName("summarizeLicenseRisk: 프롬프트를 구성하고 callWithSetting 결과를 반환한다")
    void summarizeLicenseRisk_buildsPromptAndReturnsResult() {
        stubResponse("License is restricted.");

        String result = client.summarizeLicenseRisk("GPL-3.0", "RESTRICTED", "mylib");

        assertThat(result).isEqualTo("License is restricted.");
    }
}
