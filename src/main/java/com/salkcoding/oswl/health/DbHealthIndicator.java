package com.salkcoding.oswl.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Custom database health indicator. Replaces the auto-configured Spring Boot indicator
 * so the readiness probe reports UP only when a real query can be executed.
 */
@Slf4j
@Component("dbHealthIndicator")
@RequiredArgsConstructor
public class DbHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return Health.up()
                    .withDetail("database", "reachable")
                    .build();
        } catch (SQLException e) {
            log.warn("[Health] Database check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("database", "unreachable")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
