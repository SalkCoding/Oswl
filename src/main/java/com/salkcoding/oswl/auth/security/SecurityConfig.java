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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;
    private final PermissionEvaluator oswlPermissionEvaluator;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AccessDeniedHandler accessDeniedHandler = (request, response, accessDeniedException) ->
                request.getRequestDispatcher("/error/403").forward(request, response);

        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/scan/**"))
            .sessionManagement(s -> s
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession)
                    .maximumSessions(1))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login", "/setup", "/error/**").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/icon/**", "/webjars/**", "/favicon.ico").permitAll()
                    .requestMatchers("/api/scan/**").permitAll()
                    .requestMatchers("/data/**").permitAll()
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .defaultSuccessUrl("/projects", true)
                    .failureUrl("/login?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll())
            .exceptionHandling(ex -> ex.accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(new SetupRedirectFilter(userRepository),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(oswlPermissionEvaluator);
        return handler;
    }

    /** local 프로파일에서만 활성화: H2 콘솔을 인증 없이 접근 허용. */
    @Bean
    @Profile("local")
    @Order(1)
    public SecurityFilterChain h2ConsoleFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/h2-console/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }
}
