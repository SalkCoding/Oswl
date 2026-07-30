package com.salkcoding.oswl.web.config;

import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.concurrent.TimeUnit;

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

    // ── Static resources ─────────────────────────────────────────────────

    /**
     * Long-lived cache headers (7 days, public) for static assets such as
     * tailwind.css and chart.umd.min.js. Templates reference fixed file names
     * (no content versioning), so the immutable flag is not used — browsers
     * revalidate once max-age expires.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl cacheControl = CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic();
        registry.addResourceHandler("/css/**").addResourceLocations("classpath:/static/css/").setCacheControl(cacheControl);
        registry.addResourceHandler("/js/**").addResourceLocations("classpath:/static/js/").setCacheControl(cacheControl);
        registry.addResourceHandler("/img/**").addResourceLocations("classpath:/static/img/").setCacheControl(cacheControl);
        registry.addResourceHandler("/icon/**").addResourceLocations("classpath:/static/icon/").setCacheControl(cacheControl);
        registry.addResourceHandler("/graphic/**").addResourceLocations("classpath:/static/graphic/").setCacheControl(cacheControl);
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
