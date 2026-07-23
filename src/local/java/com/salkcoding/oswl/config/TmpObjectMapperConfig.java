package com.salkcoding.oswl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * TEMPORARY local-only shim so bootRun starts while a parallel in-progress service
 * (AuditLogSiemExportService) still injects the Jackson 2 ObjectMapper.
 * DELETE THIS FILE once that service is fixed — do not commit.
 */
@Configuration
@Profile("local")
public class TmpObjectMapperConfig {

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
