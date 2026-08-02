package com.salkcoding.oswl.web.interceptor;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.apikey.ApiKey;
import com.salkcoding.oswl.domain.enums.ApiKeyScope;
import com.salkcoding.oswl.security.ClientIpResolver;
import com.salkcoding.oswl.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the Authorization: Bearer header for SCIM 2.0 endpoints.
 * Only API keys whose scope is {@link ApiKeyScope#SCIM} are accepted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScimAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_SCIM_API_KEY = "authenticatedScimApiKey";

    private final ApiKeyService apiKeyService;
    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String clientIp = clientIpResolver.resolve(request);
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            onScimFailure(clientIp, "MISSING_OR_INVALID_HEADER", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid Authorization header. Use: Authorization: Bearer <scim_token>");
            return false;
        }

        String rawToken = authHeader.substring(7).trim();
        if (rawToken.isBlank()) {
            onScimFailure(clientIp, "EMPTY_TOKEN", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Empty SCIM token");
            return false;
        }

        try {
            ApiKey apiKey = apiKeyService.validateScopedToken(rawToken, ApiKeyScope.SCIM);
            apiKey.recordUsage();
            request.setAttribute(ATTR_SCIM_API_KEY, apiKey);
            return true;
        } catch (Exception e) {
            onScimFailure(clientIp, "INVALID_SCIM_TOKEN", request.getRequestURI());
            log.warn("[ScimAuth] Authentication failed - {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }
    }

    private void onScimFailure(String clientIp, String reason, String path) {
        auditLogService.logAnonymous("scim-client", "SCIM.AUTH_FAILURE", "SCIM_KEY",
                null, null, "ip=" + clientIp + " reason=" + reason + " path=" + path);
    }
}
