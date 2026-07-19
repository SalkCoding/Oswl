package com.salkcoding.oswl.web.config;

import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;

    // ── i18n configuration ───────────────────────────────────────────────

    /**
     * Cookie-based Locale store. Until the user picks a language (?lang=ko / ?lang=en)
     * the browser's Accept-Language decides — Korean browsers start in Korean,
     * everything else falls back to English (messages.properties).
     */
    @Bean
    public LocaleResolver localeResolver() {
        // No setDefaultLocale: CookieLocaleResolver then honors Accept-Language
        return new CookieLocaleResolver("OSWL_LOCALE");
    }

    /**
     * Allows language switching using request parameters such as ?lang=ko or ?lang=en.
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    // ── View controllers ─────────────────────────────────────────────────

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/oss-notices").setViewName("oss-notices/index");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Language-switching interceptor (applies to all paths)
        registry.addInterceptor(localeChangeInterceptor());

        // Protect CLI scan ingest and ping endpoints
        registry.addInterceptor(apiKeyAuthInterceptor)
                .addPathPatterns("/api/scan/**")
                // UI polling endpoint does not require an API key
                .excludePathPatterns("/api/scan/*/status");
    }
}
