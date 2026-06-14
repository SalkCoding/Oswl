package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.entity.InstanceSetupLock;
import com.salkcoding.oswl.auth.repository.InstanceSetupLockRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("SetupRedirectFilter unit tests")
class SetupRedirectFilterTest {

    @Mock UserRepository userRepository;
    @Mock InstanceSetupLockRepository setupLockRepository;
    @Mock HttpServletRequest  request;
    @Mock HttpServletResponse response;
    @Mock FilterChain         filterChain;

    @InjectMocks SetupRedirectFilter filter;

    // -- Bypass URIs --

    @ParameterizedTest(name = "URI {0} bypasses filter")
    @ValueSource(strings = {"/setup", "/setup/", "/css/main.css", "/js/app.js",
            "/icon/favicon.ico", "/webjars/foo.js", "/error", "/actuator/health",
            "/api/scan", "/api/scan/ping"})
    void bypassUris_passThroughWithoutCheck(String uri) throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn(uri);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendRedirect(any());
        verify(userRepository, never()).existsByIsSystemAdminTrue();
    }

    // -- No admin: redirect to /setup --

    @Test
    @DisplayName("No admin exists: redirects to /setup")
    void noAdmin_redirectsToSetup() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).sendRedirect("/setup");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // -- Admin exists: pass through --

    @Test
    @DisplayName("Admin exists: request passes through")
    void adminExists_filterContinues() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendRedirect(any());
    }

    @Test
    @DisplayName("Setup lock exists: request passes through")
    void setupLockExists_filterContinues() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/projects");
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).existsByIsSystemAdminTrue();
    }

    @Test
    @DisplayName("Login URI with admin: passes through")
    void loginUri_adminExists_passesThroughToLoginPage() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/login");
        when(setupLockRepository.existsById(InstanceSetupLock.SINGLETON_ID)).thenReturn(false);
        when(userRepository.existsByIsSystemAdminTrue()).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
