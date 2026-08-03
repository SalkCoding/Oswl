package com.salkcoding.oswl.config;

import com.salkcoding.oswl.client.DepsDevClient;
import com.salkcoding.oswl.client.EpssClient;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.client.NvdClient;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
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

    @Value("${oswl.client.deps-dev.max-concurrent:24}")
    private int depsDevMaxConcurrent;

    @Value("${oswl.client.osv.connect-timeout-ms:5000}")
    private long osvConnectTimeoutMs;

    @Value("${oswl.client.osv.read-timeout-ms:30000}")
    private long osvReadTimeoutMs;

    @Value("${oswl.client.github-advisory.token:}")
    private String githubAdvisoryToken;

    @Value("${oswl.client.github-advisory.api-base:}")
    private String githubAdvisoryApiBase;

    @Value("${oswl.client.github-advisory.connect-timeout-ms:5000}")
    private long githubAdvisoryConnectTimeoutMs;

    @Value("${oswl.client.github-advisory.read-timeout-ms:20000}")
    private long githubAdvisoryReadTimeoutMs;

    @Value("${oswl.client.nvd.api-key:}")
    private String nvdApiKey;

    @Value("${oswl.client.nvd.connect-timeout-ms:5000}")
    private long nvdConnectTimeoutMs;

    @Value("${oswl.client.nvd.read-timeout-ms:20000}")
    private long nvdReadTimeoutMs;

    @Bean
    public OsvClient osvClient() {
        return new OsvClient(snapshotService, airgapped,
                Duration.ofMillis(osvConnectTimeoutMs), Duration.ofMillis(osvReadTimeoutMs));
    }

    @Bean
    public DepsDevClient depsDevClient() {
        return new DepsDevClient(snapshotService, airgapped,
                Duration.ofMillis(depsDevConnectTimeoutMs), Duration.ofMillis(depsDevReadTimeoutMs),
                depsDevMaxConcurrent);
    }

    @Bean
    public EpssClient epssClient() {
        return new EpssClient(snapshotService, airgapped);
    }

    @Bean
    public KevCatalogService kevCatalogService() {
        return new KevCatalogService(snapshotService, airgapped);
    }

    @Bean
    public GitHubAdvisoryClient gitHubAdvisoryClient() {
        return new GitHubAdvisoryClient(snapshotService, airgapped, githubAdvisoryToken, githubAdvisoryApiBase,
                Duration.ofMillis(githubAdvisoryConnectTimeoutMs), Duration.ofMillis(githubAdvisoryReadTimeoutMs));
    }

    @Bean
    public NvdClient nvdClient() {
        return new NvdClient(snapshotService, airgapped, nvdApiKey,
                Duration.ofMillis(nvdConnectTimeoutMs), Duration.ofMillis(nvdReadTimeoutMs));
    }
}
