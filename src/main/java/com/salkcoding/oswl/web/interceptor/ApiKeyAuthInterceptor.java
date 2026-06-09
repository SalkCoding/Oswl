package com.salkcoding.oswl.web.interceptor;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.exception.TooManyRequestsException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.service.ApiKeyService;
import com.salkcoding.oswl.service.ScanApiCredentialThrottleService;
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
    private final ScanApiCredentialThrottleService scanApiCredentialThrottleService;
    private final AuditLogService auditLogService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String clientIp = resolveClientIp(request);

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            onApiKeyFailure(clientIp, "MISSING_OR_INVALID_HEADER", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid Authorization header. Use: Authorization: Bearer <api_key>");
            return false;
        }

        String rawToken = authHeader.substring(7).trim();
        if (rawToken.isBlank()) {
            onApiKeyFailure(clientIp, "EMPTY_TOKEN", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Empty API key");
            return false;
        }

        try {
            scanApiCredentialThrottleService.assertApiKeyCheckAllowed(clientIp);
            ApiKey apiKey = apiKeyService.validateAndRecord(rawToken);
            request.setAttribute(ATTR_API_KEY, apiKey);
            request.setAttribute(ATTR_PROJECT_ID, apiKey.getProject().getId());
            return true;
        } catch (TooManyRequestsException e) {
            auditLogService.logAnonymous("cli-client", "SCAN.AUTH_RATE_LIMITED", "API_KEY",
                    null, null, "ip=" + clientIp + " path=" + request.getRequestURI());
            response.sendError(429, e.getMessage());
            return false;
        } catch (UnauthorizedException e) {
            onApiKeyFailure(clientIp, "INVALID_API_KEY", request.getRequestURI());
            log.warn("[ApiKeyAuth] Authentication failed - {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }
    }

    private void onApiKeyFailure(String clientIp, String reason, String path) {
        scanApiCredentialThrottleService.recordApiKeyFailure(clientIp);
        auditLogService.logAnonymous("cli-client", "SCAN.API_KEY_FAILURE", "API_KEY",
                null, null, "ip=" + clientIp + " reason=" + reason + " path=" + path);
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }
}
