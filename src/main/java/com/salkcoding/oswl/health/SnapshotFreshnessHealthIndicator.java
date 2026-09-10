package com.salkcoding.oswl.health;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Readiness indicator for air-gapped snapshot freshness. Only meaningful when
 * {@code oswl.airgapped.enabled} is true; otherwise it reports UNKNOWN so non-air-gapped
 * deployments are not marked unhealthy.
 */
@Slf4j
@Component("snapshotFreshnessHealthIndicator")
@RequiredArgsConstructor
public class SnapshotFreshnessHealthIndicator implements HealthIndicator {

    private final AirgappedSnapshotService snapshotService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgappedEnabled;

    @Value("${oswl.airgapped.staleness-warn-days:7}")
    private int stalenessWarnDays;

    @Value("${oswl.airgapped.staleness-critical-days:30}")
    private int stalenessCriticalDays;

    @Override
    public Health health() {
        if (!airgappedEnabled) {
            return Health.unknown()
                    .withDetail("mode", "air-gapped mode disabled")
                    .build();
        }

        LocalDate oldestAsOf = snapshotService.oldestSourceAsOf();
        if (oldestAsOf == null) {
            return Health.down()
                    .withDetail("snapshot", "no imported snapshot provenance")
                    .withDetail("reason", "air-gapped mode is enabled but no snapshot has been imported")
                    .build();
        }

        long daysStale = ChronoUnit.DAYS.between(oldestAsOf, LocalDate.now());
        if (daysStale > stalenessCriticalDays) {
            return Health.down()
                    .withDetail("snapshot", "critically stale")
                    .withDetail("oldestSourceAsOf", oldestAsOf.toString())
                    .withDetail("daysStale", daysStale)
                    .withDetail("criticalThresholdDays", stalenessCriticalDays)
                    .build();
        }
        if (daysStale > stalenessWarnDays) {
            return Health.unknown()
                    .withDetail("snapshot", "stale")
                    .withDetail("oldestSourceAsOf", oldestAsOf.toString())
                    .withDetail("daysStale", daysStale)
                    .withDetail("warnThresholdDays", stalenessWarnDays)
                    .build();
        }

        return Health.up()
                .withDetail("snapshot", "fresh")
                .withDetail("oldestSourceAsOf", oldestAsOf.toString())
                .withDetail("daysStale", daysStale)
                .build();
    }
}
