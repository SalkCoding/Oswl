package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.InstanceSetupLock;
import com.salkcoding.oswl.auth.repository.InstanceSetupLockRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * If no user has been created yet, redirects all authenticated and unauthenticated requests
 * to /setup except for /setup, /css, /js, /icon, /webjars, and /error.
 */
@RequiredArgsConstructor
public class SetupRedirectFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final InstanceSetupLockRepository setupLockRepository;

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

        if (!isSetupComplete()) {
            response.sendRedirect("/setup");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSetupComplete() {
        return setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)
                || userRepository.existsByIsSystemAdminTrue();
    }
}
