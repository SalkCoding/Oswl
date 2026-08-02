package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.InstanceSetupLockRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
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
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final PermissionEvaluator oswlPermissionEvaluator;
    private final OswlAuthenticationFailureHandler authenticationFailureHandler;
    private final AuditLogoutSuccessHandler auditLogoutSuccessHandler;
    private final TwoFaAuthenticationSuccessHandler twoFaAuthenticationSuccessHandler;
    private final OswlSessionExpiredStrategy oswlSessionExpiredStrategy;

    /**
     * Horizontal scaling / HA: when {@code spring.session.store-type=jdbc} is active, a
     * {@link FindByIndexNameSessionRepository} bean is auto-configured and single-session
     * enforcement must see the whole cluster's sessions, not just this instance's in-memory ones —
     * otherwise {@code maximumSessions(1)} would only be enforced per-instance and a user could hold
     * one live session per instance behind the load balancer. Falls back to the in-memory registry
     * when no such bean exists (default: single instance, in-memory Tomcat session).
     */
    @Bean
    public SessionRegistry sessionRegistry(
            org.springframework.beans.factory.ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepositoryProvider) {
        FindByIndexNameSessionRepository<? extends Session> sessionRepository = sessionRepositoryProvider.getIfAvailable();
        if (sessionRepository != null) {
            return new SpringSessionBackedSessionRegistry<>(sessionRepository);
        }
        return new SessionRegistryImpl();
    }

    /** Required so that SessionRegistry is notified of session lifecycle events. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            org.springframework.beans.factory.ObjectProvider<org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> clientRegistrations,
            org.springframework.beans.factory.ObjectProvider<org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository> relyingParties,
            org.springframework.security.core.userdetails.UserDetailsService userDetailsService,
            SessionRegistry sessionRegistry) {
        AccessDeniedHandler accessDeniedHandler = (request, response, _) -> {
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
                            // Documented CLI flow uploads manifests here; the API key still authenticates.
                            req -> "POST".equalsIgnoreCase(req.getMethod())
                                    && "/api/scan/parse".equals(req.getRequestURI()),
                            // CI/PR gate — API-key authenticated, called from CI (no browser session).
                            req -> "POST".equalsIgnoreCase(req.getMethod())
                                    && "/api/scan/gate".equals(req.getRequestURI()),
                            req -> "GET".equalsIgnoreCase(req.getMethod())
                                    && "/api/scan/ping".equals(req.getRequestURI())))
            .headers(this::applySecurityHeaders)
            .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession)
                    .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        .expiredSessionStrategy(oswlSessionExpiredStrategy)
                        .sessionRegistry(sessionRegistry))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/login/otp-verify", "/login/otp-resend", "/setup", "/error/**").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/icon/**", "/img/**", "/graphic/**", "/scripts/**", "/webjars/**", "/favicon.ico").permitAll()
                    .requestMatchers("/oss-notices").permitAll()
                    .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                    .requestMatchers("/scim/v2/**").permitAll()
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
                    .authenticationEntryPoint((request, response, _) -> {
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
                    SetupRedirectFilter.class)
            // After SecurityContextHolderFilter so the session-backed Authentication (if any) is
            // already resolved when this filter reads it for the userId MDC value.
            .addFilterAfter(new com.salkcoding.oswl.web.filter.RequestContextLoggingFilter(),
                    org.springframework.security.web.context.SecurityContextHolderFilter.class);

        // OIDC SSO — activated only when an OIDC provider is configured
        // (spring.security.oauth2.client.registration.*). Default deploys have no registration
        // bean, so nothing changes. SSO users are mapped to their existing OsWL account.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .successHandler(new OidcLoginSuccessHandler(userDetailsService))
                    .failureHandler(authenticationFailureHandler));
        }

        // SAML 2.0 SSO — activated only when a relying party is configured
        // (spring.security.saml2.relyingparty.registration.*). IdP metadata and verification
        // credentials are injected via environment variables.
        if (relyingParties.getIfAvailable() != null) {
            http.saml2Login(saml2 -> saml2
                    .loginPage("/login")
                    .successHandler(new Saml2LoginSuccessHandler(userDetailsService, userRepository, auditLogService, passwordEncoder))
                    .failureHandler(authenticationFailureHandler));
        }

        return http.build();
    }

    private void applySecurityHeaders(
            org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<?> headers) {
        headers.contentTypeOptions(Customizer.withDefaults());
        String frame = securityHeadersProperties.getFrameOptions();
        if (frame != null && !frame.equalsIgnoreCase("DISABLE")) {
            if (frame.equalsIgnoreCase("SAMEORIGIN")) {
                headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin);
            } else {
                headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
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
    public SecurityFilterChain localDevFilterChain(HttpSecurity http) {
        http
            .securityMatcher("/h2-console/**", "/data/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));
        return http.build();
    }
}
