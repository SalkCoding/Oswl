package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 아직 사용자가 생성되지 않았으면, /setup·/css·/js·/icon·/webjars·error 를
 * 제외한 모든 인증/비인증 요청을 /setup으로 리다이렉트한다.
 */
@RequiredArgsConstructor
public class SetupRedirectFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/setup")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/icon/")
                || uri.startsWith("/webjars/")
                || uri.startsWith("/error")
                || uri.startsWith("/actuator")
                || uri.startsWith("/api/scan")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!userRepository.existsByIsSystemAdminTrue()) {
            response.sendRedirect("/setup");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
