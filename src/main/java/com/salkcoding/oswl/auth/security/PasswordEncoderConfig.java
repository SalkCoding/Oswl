package com.salkcoding.oswl.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link PasswordEncoder} 빈을 {@link SecurityConfig}와 독립적으로 제공한다.
 *
 * 인코더를 별도 설정 클래스에 드는 이유는, 인증 핀로우 컴포넌트(예: 실패 핸들러)가
 * PasswordEncoder가 필요한 서비스를 주입할 때 발생하는 순환 의존성을 해결하기 위해서다.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
