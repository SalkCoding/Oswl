package com.salkcoding.oswl.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Application-wide clock pinned to the configured zone (oswl.timezone, default Asia/Seoul).
 * AI usage date bucketing uses this so "today" does not depend on the server's system timezone.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${oswl.timezone:Asia/Seoul}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
