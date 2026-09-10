package com.salkcoding.oswl.health;

import com.salkcoding.oswl.service.ai.EmbeddedAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness indicator for the embedded llama.cpp sidecar. Reports DOWN when the sidecar
 * is not running and required resources are missing; UNKNOWN when it is stopped but could
 * be started (e.g. model present but not yet launched); UP when the process answers /health.
 */
@Slf4j
@Component("embeddedSidecarHealthIndicator")
@RequiredArgsConstructor
public class EmbeddedSidecarHealthIndicator implements HealthIndicator {

    private final EmbeddedAiService embeddedAiService;

    @Override
    public Health health() {
        EmbeddedAiService.EmbeddedAiStatus status = embeddedAiService.status();

        if (status.isRunning()) {
            return Health.up()
                    .withDetail("sidecar", "running")
                    .withDetail("model", status.getActiveModel())
                    .withDetail("baseUrl", status.getBaseUrl())
                    .withDetail("external", status.isExternal())
                    .build();
        }

        if (!status.isBinaryFound()) {
            return Health.down()
                    .withDetail("sidecar", "binary missing")
                    .withDetail("binaryPath", status.getBinaryPath())
                    .withDetail("reason", status.getLastError() != null ? status.getLastError()
                            : "llama-server binary not found in sidecar directory or PATH")
                    .build();
        }

        if (status.getAvailableModels() == null || status.getAvailableModels().isEmpty()) {
            return Health.down()
                    .withDetail("sidecar", "model missing")
                    .withDetail("modelsDir", status.getModelsDir())
                    .withDetail("reason", status.getLastError() != null ? status.getLastError()
                            : "no .gguf model found in sidecar directory")
                    .build();
        }

        return Health.unknown()
                .withDetail("sidecar", "stopped")
                .withDetail("reason", status.getLastError() != null ? status.getLastError()
                        : "sidecar is not running but can be started from Settings")
                .build();
    }
}
