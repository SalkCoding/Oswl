package com.salkcoding.oswl.service.snapshot;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class SnapshotComponentIdentityTest {
    @ParameterizedTest
    @CsvSource({"npm,a|b,1", "npm,a,b|1", "npm|a,b,1"})
    void separatorsCannotAliasAnotherComponent(String ecosystem, String name, String version) {
        assertThat(AirgappedSnapshotService.componentKey(ecosystem, name, version)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"npm,@scope/example,1.0.0,NPM|@scope/example|1.0.0",
            "crates.io,example,1.0.0,CARGO|example|1.0.0", "Maven,org.example:example,1.0,MAVEN|org.example:example|1.0",
            "Debian:12,example,1:2.0-1,DEBIAN:12|example|1:2.0-1"})
    void supportedCoordinatesKeepTheirExistingKeys(String ecosystem, String name, String version, String key) {
        assertThat(AirgappedSnapshotService.componentKey(ecosystem, name, version)).isEqualTo(key);
    }
}
