package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.AiConnectionTestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.Locale;
import java.util.Optional;

/**
 * Builds user-facing connection-test messages with actionable hints.
 */
@Component
@RequiredArgsConstructor
public class AiConnectionDiagnostics {

    private final MessageSource messageSource;

    public Optional<AiConnectionTestResult> preflight(AiSetting setting) {
        AiProvider provider = setting.getProvider();
        if (provider == null) {
            return Optional.of(fail("settings.ai.test.noProvider", "settings.ai.test.noProvider.hint"));
        }
        if (isBlank(setting.getModelName())) {
            return Optional.of(fail("settings.ai.test.missingModel", "settings.ai.test.missingModel.hint"));
        }
        if (provider == AiProvider.LOCAL && isBlank(setting.getBaseUrl())) {
            return Optional.of(fail("settings.ai.test.missingBaseUrl", "settings.ai.test.missingBaseUrl.hint"));
        }
        if (provider != AiProvider.LOCAL && isBlank(setting.getApiKey())) {
            return Optional.of(fail("settings.ai.test.missingApiKey", "settings.ai.test.missingApiKey.hint"));
        }
        return Optional.empty();
    }

    public AiConnectionTestResult success() {
        return AiConnectionTestResult.ok(msg("settings.ai.msg.testOk"));
    }

    public AiConnectionTestResult dailyCapReached() {
        return fail("settings.ai.test.dailyCap", "settings.ai.test.dailyCap.hint");
    }

    public AiConnectionTestResult fromEmptyResponse(AiSetting setting) {
        return switch (setting.getProvider()) {
            case LOCAL -> fail("settings.ai.test.empty.local", "settings.ai.test.empty.local.hint",
                    setting.getModelName(), setting.getBaseUrl());
            case GEMINI -> fail("settings.ai.test.empty.gemini", "settings.ai.test.empty.gemini.hint",
                    setting.getModelName());
            case OPENAI -> fail("settings.ai.test.empty.openai", "settings.ai.test.empty.openai.hint",
                    setting.getModelName());
            case ANTHROPIC -> fail("settings.ai.test.empty.anthropic", "settings.ai.test.empty.anthropic.hint",
                    setting.getModelName());
        };
    }

    public AiConnectionTestResult fromException(AiSetting setting, Exception e) {
        if (e instanceof HttpStatusCodeException http) {
            return fromHttpStatus(setting, http);
        }
        String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String lower = raw.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "connection refused", "connectexception", "failed to connect",
                "connection reset", "no route to host")) {
            return fail("settings.ai.test.network", "settings.ai.test.network.hint",
                    endpointHint(setting));
        }
        if (containsAny(lower, "timed out", "timeout", "read timed out")) {
            return fail("settings.ai.test.timeout", "settings.ai.test.timeout.hint",
                    endpointHint(setting));
        }
        if (containsAny(lower, "unknownhost", "unknown host", "name or service not known")) {
            return fail("settings.ai.test.unknownHost", "settings.ai.test.unknownHost.hint",
                    setting.getBaseUrl() != null ? setting.getBaseUrl() : "");
        }
        if (containsAny(lower, "ssl", "certificate", "tls")) {
            return fail("settings.ai.test.ssl", "settings.ai.test.ssl.hint");
        }

        return fail("settings.ai.test.generic", "settings.ai.test.generic.hint", truncate(raw, 120));
    }

    private AiConnectionTestResult fromHttpStatus(AiSetting setting, HttpStatusCodeException http) {
        int code = http.getStatusCode().value();
        String body = http.getResponseBodyAsString();
        String bodySnippet = truncate(body, 100);

        return switch (code) {
            case 401, 403 -> fail("settings.ai.test.http401", "settings.ai.test.http401.hint",
                    setting.getProvider().name());
            case 404 -> fail("settings.ai.test.http404", "settings.ai.test.http404.hint.regexp",
                    setting.getModelName(), endpointHint(setting));
            case 429 -> fail("settings.ai.test.http429", "settings.ai.test.http429.hint");
            case 400 -> {
                if (setting.getProvider() == AiProvider.LOCAL
                        && containsAny(body.toLowerCase(Locale.ROOT), "model", "not found", "does not exist")) {
                    yield fail("settings.ai.test.localModelNotFound", "settings.ai.test.localModelNotFound.hint",
                            setting.getModelName());
                }
                yield fail("settings.ai.test.http400", "settings.ai.test.http400.hint", bodySnippet);
            }
            default -> fail("settings.ai.test.httpOther", "settings.ai.test.httpOther.hint",
                    code, bodySnippet);
        };
    }

    private String endpointHint(AiSetting setting) {
        if (setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()) {
            return setting.getBaseUrl();
        }
        return switch (setting.getProvider()) {
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case OPENAI -> "https://api.openai.com/v1";
            case ANTHROPIC -> "https://api.anthropic.com";
            case LOCAL -> "http://localhost:11434/v1";
        };
    }

    private AiConnectionTestResult fail(String messageKey, String hintKey, Object... args) {
        return AiConnectionTestResult.fail(msg(messageKey, args), msg(hintKey, args));
    }

    private String msg(String key, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(key, args, key, locale);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
