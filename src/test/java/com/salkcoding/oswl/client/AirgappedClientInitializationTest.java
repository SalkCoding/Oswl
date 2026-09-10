package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AirgappedClientInitializationTest {
    @ParameterizedTest
    @ValueSource(strings = {"OSV", "GHSA", "NVD", "DEPS_DEV", "EPSS", "KEV"})
    void missingOfflineStoreCannotSilentlyEnableNetworkMode(String client) {
        assertThatThrownBy(() -> create(client, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OSV", "GHSA", "NVD", "DEPS_DEV", "EPSS", "KEV"})
    void validOnlineAndOfflineConfigurationsRemainConstructible(String client) {
        assertThatCode(() -> create(client, null, false)).doesNotThrowAnyException();
        assertThatCode(() -> create(client, mock(AirgappedSnapshotService.class), true)).doesNotThrowAnyException();
    }

    private Object create(String name, AirgappedSnapshotService snapshots, boolean offline) {
        Duration timeout = Duration.ofSeconds(1);
        return switch (name) {
            case "OSV" -> new OsvClient(snapshots, offline);
            case "GHSA" -> new GitHubAdvisoryClient(snapshots, offline, "fixture", "https://api.github.com", timeout, timeout);
            case "NVD" -> new NvdClient(snapshots, offline, null, timeout, timeout);
            case "DEPS_DEV" -> new DepsDevClient(snapshots, offline);
            case "EPSS" -> new EpssClient(snapshots, offline);
            case "KEV" -> new KevCatalogService(snapshots, offline);
            default -> throw new IllegalArgumentException("Unknown test client");
        };
    }
}
