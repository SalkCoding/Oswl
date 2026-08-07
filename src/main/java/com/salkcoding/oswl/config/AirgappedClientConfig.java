package com.salkcoding.oswl.config;

import com.salkcoding.oswl.client.DepsDevClient;
import com.salkcoding.oswl.client.EpssClient;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.client.NvdClient;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
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
    private final OswlMetrics oswlMetrics;

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
        OsvClient client = new OsvClient(snapshotService, airgapped,
                Duration.ofMillis(osvConnectTimeoutMs), Duration.ofMillis(osvReadTimeoutMs));
        client.setOswlMetrics(oswlMetrics);
        return client;
    }

    @Bean
    public DepsDevClient depsDevClient() {
        DepsDevClient client = new DepsDevClient(snapshotService, airgapped,
                Duration.ofMillis(depsDevConnectTimeoutMs), Duration.ofMillis(depsDevReadTimeoutMs),
                depsDevMaxConcurrent);
        client.setOswlMetrics(oswlMetrics);
        return client;
    }

    @Bean
    public EpssClient epssClient() {
        EpssClient client = new EpssClient(snapshotService, airgapped);
        client.setOswlMetrics(oswlMetrics);
        return client;
    }

    @Bean
    public KevCatalogService kevCatalogService() {
        KevCatalogService client = new KevCatalogService(snapshotService, airgapped);
        client.setOswlMetrics(oswlMetrics);
        return client;
    }

    @Bean
    public GitHubAdvisoryClient gitHubAdvisoryClient() {
        GitHubAdvisoryClient client = new GitHubAdvisoryClient(snapshotService, airgapped, githubAdvisoryToken,
                githubAdvisoryApiBase,
                Duration.ofMillis(githubAdvisoryConnectTimeoutMs), Duration.ofMillis(githubAdvisoryReadTimeoutMs));
        client.setOswlMetrics(oswlMetrics);
        return client;
    }

    @Bean
    public NvdClient nvdClient() {
        NvdClient client = new NvdClient(snapshotService, airgapped, nvdApiKey,
                Duration.ofMillis(nvdConnectTimeoutMs), Duration.ofMillis(nvdReadTimeoutMs));
        client.setOswlMetrics(oswlMetrics);
        return client;
    }
}
