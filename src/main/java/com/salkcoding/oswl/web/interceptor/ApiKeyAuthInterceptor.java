package com.salkcoding.oswl.web.interceptor;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the Authorization: Bearer &lt;token&gt; header for /api/scan/** paths.
 * Injects the ApiKey and projectId into request attributes if the key is valid.
 *
 * Returns 401 Unauthorized if the key is missing or invalid.
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
            log.warn("[ApiKeyAuth] Authentication failed - {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }
    }
}
