package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiEffort;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.core.ParameterizedTypeReference;

/**
 * OpenAI Chat Completions API implementation.
 * The LOCAL provider also uses this client when its baseUrl points to an OpenAI-compatible endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient implements AiAnalysisClient {

    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    /** Google AI Studio OpenAI-compatible base (see <a href="https://ai.google.dev/gemini-api/docs/openai">...</a>). */
    public static final String DEFAULT_GEMINI_OPENAI_BASE =
            "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
    private static final String PROVIDER_TAG = "OpenAI";

    /** Streaming connect timeout — same backstop as the non-streaming RestTemplate. */
    private static final Duration STREAMING_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Total-exchange timeout for a streaming call. Unlike the non-streaming read timeout
     * (socket-idle based), the JDK {@link HttpClient} request timeout caps the WHOLE exchange,
     * and a slow local model can legitimately stream tokens for minutes — so this is a
     * deliberately generous hang backstop rather than a latency target.
     */
    private static final Duration STREAMING_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    private final AiPromptTemplateService promptTemplates;
    private final AiCallTrace callTrace;
    private final AiUsageRecorderService usageRecorder;
    private final OutboundUrlValidator outboundUrlValidator;
    private final RestTemplate restTemplate = AiRestTemplates.forCompletions();
    private final RestTemplate probeTemplate = AiRestTemplates.forProbe();
    /** Streaming path only — RestTemplate buffers the whole body, so SSE needs the JDK client. */
    private final HttpClient streamingHttpClient = HttpClient.newBuilder()
            .connectTimeout(STREAMING_CONNECT_TIMEOUT)
            .build();

    /**
     * D2: stream free-form AI calls (posture/trend/diff) token-by-token so Quick Import can show
     * a live preview. Field-injected (not constructor) so plain-Mockito unit tests keep the
     * Java default {@code false} — the non-streaming path they were written against.
     */
    @Value("${oswl.ai.streaming-enabled:true}")
    private boolean streamingEnabled;

    // ── Called only within the package (delegated by AiAnalysisService) ───────────────

    @Override
    public String summarizeCve(String cveId, String severity, double cvssScore,
                               String cveType, String component) {
        return call(promptTemplates.cveSingleWithType(cveId, severity, cvssScore, cveType, component),
                null, "cve.single", null);
    }

    @Override
    public String summarizeLicenseRisk(String licenseName, String licenseStatus, String component) {
        return call(promptTemplates.licenseSingle(licenseName, licenseStatus, component,
                null, "unknown", null), null, "license.single", null);
    }

    /**
     * Calls the API using apiKey / baseUrl / modelName stored in AiSetting.
     * Executed with the setting injected by AiAnalysisService.
     */
    public String callWithSetting(String prompt, AiSetting setting) {
        return callWithSetting(prompt, setting, "completion");
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation) {
        return callWithSetting(prompt, setting, operation, null);
    }

    public String callWithSetting(String prompt, AiSetting setting, String operation, String resolvedApiKey) {
        return call(prompt, setting, operation, resolvedApiKey);
    }

    /**
     * Free-form variant with a live chunk sink. Streams tokens over OpenAI-compatible SSE
     * (llama.cpp uses the same format), pushing each delta to {@code chunkSink} as it arrives.
     * Falls back to the plain non-streaming call when streaming is disabled, the endpoint
     * rejects the streaming request, or the stream breaks mid-flight — the caller's contract
     * (nullable String result) is unchanged either way. Batch/JSON callers should keep using
     * the 4-arg overload: raw partial JSON is meaningless as a user-facing preview.
     */
    public String callWithSetting(String prompt, AiSetting setting, String operation, String resolvedApiKey,
                                  Consumer<String> chunkSink) {
        if (!streamingEnabled || chunkSink == null) {
            return call(prompt, setting, operation, resolvedApiKey);
        }
        String streamed = callStreaming(prompt, setting, operation, resolvedApiKey, chunkSink);
        if (streamed != null) {
            return streamed;
        }
        log.warn("[AI][{}] Streaming unavailable (op={}) — falling back to non-streaming", PROVIDER_TAG, operation);
        return call(prompt, setting, operation, resolvedApiKey);
    }

    /**
     * Free connection probe: {@code GET {base}/models} lists the caller's available models.
     * It exercises exactly what a connection test needs — DNS, TLS, reachability and
     * credentials — without generating a single token, so testing a provider costs nothing
     * and does not draw down the daily call cap. Covers OpenAI, Gemini's OpenAI-compatible
     * endpoint and any OpenAI-compatible local runtime (Ollama, LM Studio, llama.cpp).
     *
     * @return the model ids the endpoint reported, empty when it returned no parseable list
     * @throws org.springframework.web.client.RestClientException on any transport or HTTP error,
     *         which {@link AiConnectionDiagnostics} turns into an actionable message
     */
    public List<String> probeModels(AiSetting setting, String resolvedApiKey) {
        String url = resolveModelsUrl(setting);
        HttpHeaders headers = new HttpHeaders();
        if (resolvedApiKey != null && !resolvedApiKey.isBlank()) {
            headers.setBearerAuth(resolvedApiKey);
        }
        log.debug("[AI][{}] probe → GET {}", PROVIDER_TAG, url);
        ResponseEntity<Map<String, Object>> response = probeTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        return extractModelIds(response.getBody());
    }

    /** Reads the {@code data[].id} list an OpenAI-compatible {@code /models} response returns. */
    private static List<String> extractModelIds(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof List<?> data)) return List.of();
        return data.stream()
                .filter(Map.class::isInstance)
                .map(entry -> ((Map<?, ?>) entry).get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private String resolveModelsUrl(AiSetting setting) {
        // resolveUrl() appends /chat/completions; the models endpoint is its sibling.
        String completions = resolveUrl(setting);
        return completions.endsWith("/chat/completions")
                ? completions.substring(0, completions.length() - "/chat/completions".length()) + "/models"
                : completions;
    }

    // ── Streaming ────────────────────────────────────────────────────────

    /**
     * One streaming chat-completion call: {@code stream: true} + SSE parsing. Returns the full
     * accumulated text, or {@code null} when the endpoint cannot stream / the call failed — the
     * caller then falls back to the non-streaming path. Token usage is requested via
     * {@code stream_options.include_usage} and recorded from the final chunk when the server
     * supports it; otherwise usage recording is quietly skipped (no exception).
     */
    private String callStreaming(String userPrompt, AiSetting setting, String operation,
                                 String resolvedApiKey, Consumer<String> chunkSink) {
        String url   = resolveUrl(setting);
        String model = resolveModel(setting);
        boolean hasAuth = resolvedApiKey != null && !resolvedApiKey.isBlank();
        String op = operation != null ? operation : "completion";

        if (!hasAuth && DEFAULT_OPENAI_URL.equals(url)) {
            return null; // the non-streaming fallback logs the missing-key warning
        }

        long start = System.currentTimeMillis();
        StringBuilder result = new StringBuilder();
        Map<String, Object> usage = null;
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system",
                                   "content", promptTemplates.getSystemPrompt(setting.getProvider())),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    "max_tokens", promptTemplates.getMaxTokens(op, providerOf(setting)),
                    "temperature", promptTemplates.getTemperature(),
                    "stream", true,
                    // Servers without stream_options support either ignore it or reject the
                    // request — a reject surfaces as a non-2xx below and triggers the fallback.
                    "stream_options", Map.of("include_usage", true)
            ));
            applyReasoningEffort(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(STREAMING_REQUEST_TIMEOUT)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(STREAM_MAPPER.writeValueAsString(body)));
            if (hasAuth) {
                requestBuilder.header("Authorization", "Bearer " + resolvedApiKey);
            }

            log.debug("[AI][{}] → stream url='{}' model='{}' auth={} promptLen={}",
                    PROVIDER_TAG, url, model, hasAuth ? "Bearer" : "none", userPrompt.length());

            HttpResponse<java.io.InputStream> response = streamingHttpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[AI][{}] Streaming request rejected (HTTP {}) op={} — will retry non-streaming",
                        PROVIDER_TAG, response.statusCode(), op);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) break;
                    JsonNode chunk = STREAM_MAPPER.readTree(payload);
                    if (usage == null && chunk.hasNonNull("usage")) {
                        usage = STREAM_MAPPER.convertValue(chunk.get("usage"), new TypeReference<>() {});
                    }
                    String delta = extractStreamDelta(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        result.append(delta);
                        chunkSink.accept(delta);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AI][{}] Streaming call interrupted op={} — will retry non-streaming", PROVIDER_TAG, op);
            return null;
        } catch (Exception e) {
            log.warn("[AI][{}] Streaming call failed after {}ms op={} — {}: {} (will retry non-streaming)",
                    PROVIDER_TAG, System.currentTimeMillis() - start, op,
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }

        if (usage != null) {
            usageRecorder.recordFromOpenAiUsage(Map.of("usage", usage), setting.getProvider(), op, model);
        } else {
            // Server ignored include_usage — token usage is simply not recorded for this call.
            log.debug("[AI][{}] Streaming response carried no usage chunk — usage not recorded (op={})",
                    PROVIDER_TAG, op);
        }
        log.debug("[AI][{}] ← stream complete op={} elapsedMs={} resultLen={}",
                PROVIDER_TAG, op, System.currentTimeMillis() - start, result.length());
        String text = result.toString().strip();
        return text.isEmpty() ? null : text;
    }

    /** Reads {@code choices[0].delta.content} from one streaming chunk; absent on the final usage chunk. */
    private static String extractStreamDelta(JsonNode chunk) {
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return null;
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null) return null;
        JsonNode content = delta.get("content");
        return content != null && content.isTextual() ? content.asText() : null;
    }

    // ── Internal ─────────────────────────────────────────────────────────────────

    private String call(String userPrompt, AiSetting setting, String operation, String resolvedApiKey) {
        String url   = resolveUrl(setting);
        String model = resolveModel(setting);
        boolean hasAuth = resolvedApiKey != null && !resolvedApiKey.isBlank();
        String op = operation != null ? operation : "completion";
        String detail = "model=" + model + " promptLen=" + userPrompt.length();

        callTrace.logPromptExcerpt(log, PROVIDER_TAG, op, userPrompt);

        if (!hasAuth && DEFAULT_OPENAI_URL.equals(url)) {
            log.warn("[AI][{}] API key is not configured. Skipping.", PROVIDER_TAG);
            return null;
        }

        log.debug("[AI][{}] → url='{}' model='{}' auth={} promptLen={}",
                PROVIDER_TAG, url, model, hasAuth ? "Bearer" : "none", userPrompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // LOCAL (for example, Ollama) does not require an API key — set Authorization only when a key exists
        if (hasAuth) {
            headers.setBearerAuth(resolvedApiKey);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system",
                               "content", promptTemplates.getSystemPrompt(setting.getProvider())),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", promptTemplates.getMaxTokens(op, providerOf(setting)),
                "temperature", promptTemplates.getTemperature()
        ));
        applyReasoningEffort(body);

        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AiCallTrace.Session _ = callTrace.begin(log, PROVIDER_TAG, op, detail + " attempt=" + attempt)) {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(body, headers),
                        new ParameterizedTypeReference<>() {});

                long elapsed = System.currentTimeMillis() - start;
                log.debug("[AI][{}] ← status={} elapsedMs={} attempt={}", PROVIDER_TAG, response.getStatusCode(), elapsed, attempt);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    AiProvider providerTag = setting.getProvider();
                    usageRecorder.recordFromOpenAiUsage(response.getBody(), providerTag, op, model);
                    var choices = (List<?>) response.getBody().get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        var message = (Map<?, ?>) ((Map<?, ?>) choices.getFirst()).get("message");
                        String result = message != null ? extractContent(message.get("content")) : null;
                        if (result != null) result = result.strip();
                        callTrace.logAssistantMessage(log, PROVIDER_TAG, op, result, message);
                        log.debug("[AI][{}] Parsed result resultLen={}", PROVIDER_TAG, result != null ? result.length() : 0);
                        if (result != null && !result.isBlank()) return result;
                        log.warn("[AI][{}] Empty result on attempt {} — giving up (retry is AiAnalysisService's responsibility)", PROVIDER_TAG, attempt);
                        return null;
                    }
                    log.warn("[AI][{}] Response body has no 'choices' — keys={}", PROVIDER_TAG, response.getBody().keySet());
                    return null;
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if ((msg.contains("429") || msg.contains("RateLimitReached") || msg.contains("TooManyRequests")) && attempt < 2) {
                    int waitSec = parseRateLimitWaitSeconds(msg);
                    log.warn("[AI][{}] Rate limited — waiting {}s before retry (attempt {})", PROVIDER_TAG, waitSec, attempt);
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                } else {
                    log.error("[AI][{}] Call failed after {}ms attempt={} — {}: {}", PROVIDER_TAG, elapsed, attempt, e.getClass().getSimpleName(), e.getMessage());
                    if ("test.connection".equals(op)) {
                        throw (RuntimeException) e;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** Provider of the setting in play; OPENAI when a caller supplied none (schema-less prompts). */
    private static AiProvider providerOf(AiSetting setting) {
        return setting != null && setting.getProvider() != null ? setting.getProvider() : AiProvider.OPENAI;
    }

    /**
     * Adds {@code reasoning_effort} when the user picked an effort level in AI settings.
     * Left out entirely on {@link AiEffort#DEFAULT} — non-reasoning models and older
     * OpenAI-compatible runtimes reject unknown sampling fields, so opting in is a choice.
     * Anthropic is not routed through this client; it uses {@code output_config.effort}.
     */
    private void applyReasoningEffort(Map<String, Object> body) {
        String effort = promptTemplates.getReasoningEffort().openAiValue();
        if (effort != null) {
            body.put("reasoning_effort", effort);
        }
    }

    /**
     * message.content is normally a string, but some OpenAI-compatible servers
     * (local runtimes, Gemini compat layer) return an array of content parts.
     */
    private static String extractContent(Object content) {
        switch (content) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case List<?> parts -> {
                StringBuilder sb = new StringBuilder();
                for (Object part : parts) {
                    if (part instanceof String s) {
                        sb.append(s);
                    } else if (part instanceof Map<?, ?> m) {
                        Object text = m.get("text");
                        if (text instanceof String s) sb.append(s);
                    }
                }
                return sb.isEmpty() ? null : sb.toString();
            }
            default -> {
            }
        }
        return String.valueOf(content);
    }

    private static int parseRateLimitWaitSeconds(String message) {
        if (message == null) return 30;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("wait (\\d+) second").matcher(message);
        return m.find() ? Math.min(Integer.parseInt(m.group(1)) + 2, 60) : 30;
    }

    private String resolveUrl(AiSetting setting) {
        if (setting != null && setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            String base = setting.getBaseUrl();
            if (setting.getProvider() == AiProvider.LOCAL) {
                outboundUrlValidator.validateLocalAiBaseUrl(base);
            } else {
                outboundUrlValidator.validateHttpUrl(base);
            }
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        }
        if (setting != null && setting.getProvider() == AiProvider.GEMINI) {
            return DEFAULT_GEMINI_OPENAI_BASE + "/chat/completions";
        }
        return DEFAULT_OPENAI_URL;
    }

    private String resolveModel(AiSetting setting) {
        if (setting != null && setting.getModelName() != null && !setting.getModelName().isBlank()) {
            return setting.getModelName();
        }
        if (setting != null && setting.getProvider() == AiProvider.GEMINI) {
            return DEFAULT_GEMINI_MODEL;
        }
        return "gpt-4o-mini";
    }
}
