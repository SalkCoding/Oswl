package com.salkcoding.oswl.auth.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;

/** Explicit opt-in: the Spring Session library alone does not configure Boot's servlet integration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.session.store-type", havingValue = "jdbc")
@EnableJdbcHttpSession
public class JdbcSessionConfig {
    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> jdbcSessionSettings(
            @Value("${spring.session.timeout:${server.servlet.session.timeout:30m}}") String timeout,
            @Value("${spring.session.jdbc.table-name:SPRING_SESSION}") String tableName) {
        return repository -> {
            repository.setDefaultMaxInactiveInterval(DurationStyle.detectAndParse(timeout));
            repository.setTableName(tableName);
        };
    }

    @Bean
    FilterRegistrationBean<SessionRepositoryFilter<?>> jdbcSessionFilterRegistration(
            SessionRepositoryFilter<?> filter) {
        var registration = new FilterRegistrationBean<SessionRepositoryFilter<?>>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
