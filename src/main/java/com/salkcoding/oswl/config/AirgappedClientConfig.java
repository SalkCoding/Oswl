package com.salkcoding.oswl.config;

import com.salkcoding.oswl.client.DepsDevClient;
import com.salkcoding.oswl.client.EpssClient;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.AirgappedSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the external-API clients. The snapshot store and the air-gapped flag are
 * passed in explicitly so the clients keep their public no-arg constructors
 * (used directly by unit tests) and serve live HTTP by default.
 */
@Configuration
@RequiredArgsConstructor
public class AirgappedClientConfig {

    private final AirgappedSnapshotService snapshotService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    @Value("${oswl.client.deps-dev.connect-timeout-ms:5000}")
    private long depsDevConnectTimeoutMs;

    @Value("${oswl.client.deps-dev.read-timeout-ms:10000}")
    private long depsDevReadTimeoutMs;

    @Value("${oswl.client.osv.connect-timeout-ms:5000}")
    private long osvConnectTimeoutMs;

    @Value("${oswl.client.osv.read-timeout-ms:30000}")
    private long osvReadTimeoutMs;

    @Bean
    public OsvClient osvClient() {
        return new OsvClient(snapshotService, airgapped,
                Duration.ofMillis(osvConnectTimeoutMs), Duration.ofMillis(osvReadTimeoutMs));
    }

    @Bean
    public DepsDevClient depsDevClient() {
        return new DepsDevClient(snapshotService, airgapped,
                Duration.ofMillis(depsDevConnectTimeoutMs), Duration.ofMillis(depsDevReadTimeoutMs));
    }

    @Bean
    public EpssClient epssClient() {
        return new EpssClient(snapshotService, airgapped);
    }

    @Bean
    public KevCatalogService kevCatalogService() {
        return new KevCatalogService(snapshotService, airgapped);
    }
}
