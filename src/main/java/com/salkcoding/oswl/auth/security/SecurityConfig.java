package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
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

        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/api/scan/**", "/api/cli/**"))
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
                    .requestMatchers("/landing", "/landing/**").permitAll()
                    .requestMatchers("/api/scan/**").permitAll()
                    .requestMatchers("/api/cli/**").permitAll()
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
                    // REST API 요청 (Accept: application/json 또는 /api/**) 에는 302 대신 401 반환
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
            .addFilterBefore(new SetupRedirectFilter(userRepository),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new MustChangePasswordFilter(),
                    SetupRedirectFilter.class);

        return http.build();
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(oswlPermissionEvaluator);
        return handler;
    }

    /** local 프로파일에서만 활성화: H2 콘솔 및 테스트 데이터 엔드포인트를 인증 없이 접근 허용. */
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
