package com.salkcoding.oswl.web.interceptor;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/scan/** 경로에 대해 Authorization: Bearer &lt;token&gt; 헤더를 검증한다.
 * 유효한 키라면 request attribute에 ApiKey와 projectId를 주입한다.
 *
 * 키가 없거나 유효하지 않으면 401 Unauthorized를 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_API_KEY    = "authenticatedApiKey";
    public static final String ATTR_PROJECT_ID = "authenticatedProjectId";

    private final ApiKeyService apiKeyService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid Authorization header. Use: Authorization: Bearer <api_key>");
            return false;
        }

        String rawToken = authHeader.substring(7).trim();
        if (rawToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Empty API key");
            return false;
        }

        try {
            ApiKey apiKey = apiKeyService.validateAndRecord(rawToken);
            request.setAttribute(ATTR_API_KEY, apiKey);
            request.setAttribute(ATTR_PROJECT_ID, apiKey.getProject().getId());
            return true;
        } catch (UnauthorizedException e) {
            log.warn("[ApiKeyAuth] 인증 실패 - {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }
    }
}
