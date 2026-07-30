package com.salkcoding.oswl.scheduler;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Horizontal scaling / HA: cluster-wide dedup for {@code @Scheduled} jobs via ShedLock, so a
 * multi-instance deployment doesn't run the nightly monitoring / defer-expiry / trash-cleanup
 * jobs once per instance. Disabled by default (single-instance behavior is unaffected) — enable
 * with {@code OSWL_SCHEDULER_LOCK_ENABLED=true} only alongside a multi-instance deployment, and
 * only once the {@code shedlock} table exists (db/migration/V10 or
 * db/spring_session_and_shedlock.sql).
 */
@Configuration
@ConditionalOnProperty(prefix = "oswl.scheduler-lock", name = "enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
