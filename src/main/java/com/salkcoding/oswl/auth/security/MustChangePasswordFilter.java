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
 * {@code mustChangePassword} 플래그가 여전히 {@code true}인 완전 인증 사용자의
 * 모든 요청을 가로막고 {@code /change-password}로 리다이렉트한다.
 *
 * <p>API 호출(path가 {@code /api/}로 시작하거나 Accept에 {@code application/json} 포함)은
 * HTTP 리다이렉트 대신 {@code redirectUrl} 힌트가 담긴 JSON 403을 반환한다.
 *
 * <p>다음 경로는 비밀번호 변경 흐름이 항상 작동하도록 화이트리스트된다:
 * <ul>
 *   <li>{@code /change-password} 및 {@code /api/change-password}</li>
 *   <li>{@code /logout}</li>
 *   <li>{@code /login}, {@code /setup}, {@code /error/}</li>
 *   <li>정적 자산({@code /css/}, {@code /js/}, {@code /icon/}, {@code /img/},
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
