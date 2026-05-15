package com.salkcoding.oswl.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the {@link PasswordEncoder} bean independently of {@link SecurityConfig}.
 *
 * Keeping the encoder in its own configuration class breaks the circular dependency
 * that occurs when authentication-flow components (e.g. failure handlers) inject
 * services that themselves need a PasswordEncoder.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
