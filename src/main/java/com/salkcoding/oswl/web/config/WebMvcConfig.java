package com.salkcoding.oswl.web.config;

import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.util.Locale;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;

    // ── i18n 설정 ────────────────────────────────────────────────────────

    /**
     * 세션 기반 Locale 저장소. 기본값은 한국어(ko_KR).
     * ?lang=en 파라미터로 런타임에 언어를 변경할 수 있다.
     */
    @Bean
    public LocaleResolver localeResolver() {
        SessionLocaleResolver resolver = new SessionLocaleResolver();
        resolver.setDefaultLocale(Locale.KOREA);  // ko_KR → messages_ko_KR.properties
        return resolver;
    }

    /**
     * ?lang=ko, ?lang=en 등 요청 파라미터로 언어를 전환할 수 있게 한다.
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    // ── 뷰 컨트롤러 ──────────────────────────────────────────────────────

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/landing", "/landing/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 언어 전환 인터셉터 (모든 경로에 적용)
        registry.addInterceptor(localeChangeInterceptor());

        // CLI 스캔 인제스트 및 핑 엔드포인트 보호
        registry.addInterceptor(apiKeyAuthInterceptor)
                .addPathPatterns("/api/scan/**")
                // UI 폴링 엔드포인트는 API 키 불필요
                .excludePathPatterns("/api/scan/*/status");
    }
}
