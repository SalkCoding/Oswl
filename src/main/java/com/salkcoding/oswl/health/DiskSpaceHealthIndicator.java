package com.salkcoding.oswl.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Custom disk-space health indicator. Replaces the auto-configured Spring Boot indicator
 * so the threshold and monitored path are consistent with OsWL's working directory.
 */
@Slf4j
@Component("diskSpaceHealthIndicator")
@RequiredArgsConstructor
public class DiskSpaceHealthIndicator implements HealthIndicator {

    @Value("${oswl.health.disk.path:.}")
    private String path;

    @Value("${oswl.health.disk.threshold-bytes:1073741824}")
    private long thresholdBytes;

    @Override
    public Health health() {
        Path monitored = Path.of(path).toAbsolutePath().normalize();
        try {
            FileStore store = Files.getFileStore(monitored);
            long usable = store.getUsableSpace();
            long total = store.getTotalSpace();
            long freePercent = total > 0 ? (usable * 100) / total : 0;

            Health.Builder builder = usable < thresholdBytes ? Health.down() : Health.up();
            return builder
                    .withDetail("path", monitored.toString())
                    .withDetail("freeBytes", usable)
                    .withDetail("totalBytes", total)
                    .withDetail("freePercent", freePercent)
                    .withDetail("thresholdBytes", thresholdBytes)
                    .build();
        } catch (IOException e) {
            log.warn("[Health] Disk space check failed for {}: {}", monitored, e.getMessage());
            return Health.down()
                    .withDetail("path", monitored.toString())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
