package com.salkcoding.oswl.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Intercepts all requests from fully authenticated users whose {@code mustChangePassword}
 * flag is still {@code true} and redirects them to {@code /change-password}.
 *
 * <p>API calls (paths starting with {@code /api/} or requests whose Accept header includes
 * {@code application/json}) receive a JSON 403 containing a {@code redirectUrl} hint instead
 * of an HTTP redirect.
 *
 * <p>The following paths are whitelisted so the password-change flow always works:
 * <ul>
 *   <li>{@code /change-password} and {@code /api/change-password}</li>
 *   <li>{@code /logout}</li>
 *   <li>{@code /login}, {@code /setup}, {@code /error/}</li>
 *   <li>Static assets ({@code /css/}, {@code /js/}, {@code /icon/}, {@code /img/},
 *       {@code /graphic/}, {@code /scripts/}, {@code /webjars/}, {@code /favicon.ico})</li>
 * </ul>
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        if (isPassThrough(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof OswlUserPrincipal principal
                && principal.isMustChangePassword()) {

            if (isApiRequest(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"error\":\"PASSWORD_CHANGE_REQUIRED\",\"redirectUrl\":\"/change-password\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/change-password");
            }
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPassThrough(String uri) {
        return uri.equals("/change-password")
                || uri.startsWith("/api/change-password")
                || uri.startsWith("/logout")
                || uri.startsWith("/login")
                || uri.startsWith("/setup")
                || uri.startsWith("/error/")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/icon/")
                || uri.startsWith("/img/")
                || uri.startsWith("/graphic/")
                || uri.startsWith("/scripts/")
                || uri.startsWith("/webjars/")
                || uri.equals("/favicon.ico");
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getRequestURI().startsWith("/api/")
                || (accept != null && accept.contains("application/json"));
    }
}
