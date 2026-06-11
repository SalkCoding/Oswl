package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.InstanceSetupLockRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(OswlSecurityHeadersProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final OswlSecurityHeadersProperties securityHeadersProperties;
    private final UserRepository userRepository;
    private final InstanceSetupLockRepository setupLockRepository;
    private final PermissionEvaluator oswlPermissionEvaluator;
    private final OswlAuthenticationFailureHandler authenticationFailureHandler;
    private final AuditLogoutSuccessHandler auditLogoutSuccessHandler;
    private final TwoFaAuthenticationSuccessHandler twoFaAuthenticationSuccessHandler;
    private final OswlSessionExpiredStrategy oswlSessionExpiredStrategy;

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Required so that SessionRegistry is notified of session lifecycle events. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AccessDeniedHandler accessDeniedHandler = (request, response, accessDeniedException) -> {
            String accept = request.getHeader("Accept");
            String uri = request.getRequestURI();
            if (uri.startsWith("/api/") || (accept != null && accept.contains("application/json"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Forbidden\",\"status\":403}");
            } else {
                request.getRequestDispatcher("/error/403").forward(request, response);
            }
        };

        CookieCsrfTokenRepository csrfTokenRepository = new CookieCsrfTokenRepository();
        csrfTokenRepository.setCookieName("XSRF-TOKEN");
        csrfTokenRepository.setHeaderName("X-XSRF-TOKEN");
        csrfTokenRepository.setCookiePath("/");
        // Non-HttpOnly so oswl-csrf.js can fall back to the cookie when the meta tag is absent.
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie.httpOnly(false));

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(csrfRequestHandler)
                    .ignoringRequestMatchers(
                            req -> "POST".equalsIgnoreCase(req.getMethod())
                                    && "/api/scan".equals(req.getRequestURI()),
                            req -> "GET".equalsIgnoreCase(req.getMethod())
                                    && "/api/scan/ping".equals(req.getRequestURI())))
            .headers(headers -> applySecurityHeaders(headers))
            .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession)
                    .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        .expiredSessionStrategy(oswlSessionExpiredStrategy)
                        .sessionRegistry(sessionRegistry()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/login/otp-verify", "/login/otp-resend", "/setup", "/error/**").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/icon/**", "/img/**", "/graphic/**", "/scripts/**", "/webjars/**", "/favicon.ico").permitAll()
                    .requestMatchers("/oss-notices").permitAll()
                    .requestMatchers("/api/scan/**").permitAll()
                    .requestMatchers("/actuator/**").hasRole("SYSTEM_ADMIN")
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .successHandler(twoFaAuthenticationSuccessHandler)
                    .failureHandler(authenticationFailureHandler)
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessHandler(auditLogoutSuccessHandler)
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll())
            .exceptionHandling(ex -> ex
                    .accessDeniedHandler(accessDeniedHandler)
                    // Return 401 instead of 302 for REST API requests (Accept: application/json or /api/**)
                    .authenticationEntryPoint((request, response, authException) -> {
                        String accept = request.getHeader("Accept");
                        String uri = request.getRequestURI();
                        if (uri.startsWith("/api/") || (accept != null && accept.contains("application/json"))) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"error\":\"Unauthorized\",\"status\":401}");
                        } else {
                            response.sendRedirect("/login");
                        }
                    }))
            .addFilterBefore(new SetupRedirectFilter(userRepository, setupLockRepository),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new MustChangePasswordFilter(),
                    SetupRedirectFilter.class);

        return http.build();
    }

    private void applySecurityHeaders(
            org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<?> headers) {
        headers.contentTypeOptions(Customizer.withDefaults());
        String frame = securityHeadersProperties.getFrameOptions();
        if (frame != null && !frame.equalsIgnoreCase("DISABLE")) {
            if (frame.equalsIgnoreCase("SAMEORIGIN")) {
                headers.frameOptions(fo -> fo.sameOrigin());
            } else {
                headers.frameOptions(fo -> fo.deny());
            }
        }
        if (securityHeadersProperties.isHstsEnabled()) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(securityHeadersProperties.getHstsMaxAgeSeconds())
                    .includeSubDomains(securityHeadersProperties.isHstsIncludeSubDomains())
                    .preload(securityHeadersProperties.isHstsPreload())
                    .requestMatcher(new ForwardedHttpsRequestMatcher()));
        }
        String csp = securityHeadersProperties.getContentSecurityPolicy();
        if (csp != null && !csp.isBlank()) {
            headers.contentSecurityPolicy(cspConfig -> cspConfig.policyDirectives(csp.trim()));
        }
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(oswlPermissionEvaluator);
        return handler;
    }

    /** Enabled only for the local profile: allows unauthenticated access to the H2 console and test-data endpoints. */
    @Bean
    @Profile("local")
    @Order(1)
    public SecurityFilterChain localDevFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/h2-console/**", "/data/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }
}
