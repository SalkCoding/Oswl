package com.salkcoding.oswl.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Logs a prominent warning when the application is not running with the {@code prod} profile,
 * to reduce the risk of deploying with {@code local} defaults (H2, Swagger, dev encryption key, etc.).
 */
@Slf4j
@Component
public class ProductionProfileStartupWarning implements ApplicationListener<ApplicationReadyEvent> {

    private static final String SEPARATOR = "=".repeat(72);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String[] profiles = env.getActiveProfiles();
        boolean prodActive = Arrays.stream(profiles)
                .anyMatch(p -> "prod".equalsIgnoreCase(p.trim()));

        if (prodActive) {
            return;
        }

        String profileLabel = profiles.length == 0
                ? "(none — check spring.profiles.active; default may be 'local')"
                : Arrays.stream(profiles).map(String::trim).collect(Collectors.joining(", "));

        log.warn("""
                
                {}
                 SECURITY WARNING — NOT RUNNING WITH 'prod' PROFILE
                {}
                 Active profile(s): {}
                
                 If this instance is reachable from a network you do not fully trust, STOP NOW and restart with:
                   SPRING_PROFILES_ACTIVE=prod
                
                 Without 'prod', you may expose: H2 console, Swagger, /data test endpoints,
                 committed dev encryption keys, and other local-only settings.
                {}
                """,
                SEPARATOR, SEPARATOR, profileLabel, SEPARATOR);
    }
}
