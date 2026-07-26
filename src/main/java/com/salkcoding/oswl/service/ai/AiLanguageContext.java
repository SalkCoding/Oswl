package com.salkcoding.oswl.service.ai;

import java.util.Locale;

/**
 * Thread-scoped answer language for AI calls, so an AI Insight comes back in the language of the
 * person who triggered the scan rather than a single instance-wide setting.
 *
 * <p>The prompt <em>templates</em> are chosen by the global {@code promptsLocale} overlay
 * ({@code prompts_ko}, {@code prompts_ja}, …); this context adds a per-request directive telling
 * the model which language to answer in. Enrichment runs asynchronously, so the originating
 * locale is persisted on the scan and re-bound here when the pipeline starts.
 *
 * <p>Same threading contract as {@link AiUsageContext}: the AI call and its recorder run on the
 * binding thread, and scopes restore the previous value on close so they nest safely.
 */
public final class AiLanguageContext {

    private static final ThreadLocal<String> LANGUAGE = new ThreadLocal<>();

    private AiLanguageContext() {}

    /** English name of the bound language (e.g. "Korean"), or {@code null} when unset. */
    public static String currentLanguageName() {
        return LANGUAGE.get();
    }

    /**
     * Binds the answer language for the current thread from a locale/language tag
     * (e.g. {@code ko}, {@code ja}, {@code en}). Unknown or blank tags bind nothing.
     */
    public static Scope scope(String localeTag) {
        String previous = LANGUAGE.get();
        String name = languageNameOf(localeTag);
        if (name != null) {
            LANGUAGE.set(name);
        } else {
            LANGUAGE.remove();
        }
        return () -> {
            if (previous != null) {
                LANGUAGE.set(previous);
            } else {
                LANGUAGE.remove();
            }
        };
    }

    /** Maps a locale tag to the English language name used in the prompt directive. */
    public static String languageNameOf(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) return null;
        String code = localeTag.strip().toLowerCase(Locale.ROOT);
        int sep = code.indexOf('-');
        if (sep < 0) sep = code.indexOf('_');
        if (sep > 0) code = code.substring(0, sep);
        return switch (code) {
            case "ko" -> "Korean";
            case "ja" -> "Japanese";
            case "en" -> "English";
            default -> null;
        };
    }

    /** AutoCloseable that restores the previous language binding; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
