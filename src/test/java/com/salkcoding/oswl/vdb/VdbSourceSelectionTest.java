package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VdbSourceSelectionTest {
    @ParameterizedTest
    @ValueSource(strings = {"github-advisory", "nvd", "osv,github-advisory", "osv,nvd"})
    void unsupportedCollectorsFailBeforeReplacingOutput(String sources, @TempDir Path directory) throws Exception {
        Path output = directory.resolve("bundle.zip");
        Files.writeString(output, "existing bundle");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", sources,
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing bundle");
    }

    @Test
    void defaultsOnlySelectConnectedCollectors() {
        assertThat(VdbBuildOptions.parse(List.of()).sources()).containsExactly("osv", "epss", "kev", "depsdev");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--github-advisory-token", "--github-api-base", "--nvd-api-key"})
    void disconnectedCollectorOptionsAreRejected(String option) {
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--sources", "osv", option, "unused-value")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(option);
    }
}
