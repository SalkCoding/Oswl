package com.salkcoding.oswl.web.config;

import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/landing", "/landing/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuthInterceptor)
                // Protect CLI scan ingest and ping endpoints
                .addPathPatterns("/api/scan/**")
                // Exclude the UI polling endpoint — no API key required
                .excludePathPatterns("/api/scan/*/status");
    }
}
