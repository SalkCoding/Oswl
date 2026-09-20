package com.salkcoding.oswl.vdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VdbDistributionProfileTest {
    @ParameterizedTest
    @ValueSource(strings = {"epss", "kev", "depsdev", "osv,epss"})
    void selectedProfileRejectsOtherSourcesBeforeCollection(String sources) {
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed",
                "--wanted", "wanted.jsonl", "--sources", sources))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitProfileRequiresKnownPolicyAndWantedScope() {
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "approved")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed", "--wanted", "wanted.jsonl")).sources())
                .containsExactly("osv");
        assertThat(VdbBuildOptions.parse(List.of()).distributionProfile()).isEqualTo("unreviewed");
    }
}
