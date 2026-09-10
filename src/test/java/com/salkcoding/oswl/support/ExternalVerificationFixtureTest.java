package com.salkcoding.oswl.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ExternalVerificationFixtureTest {
    @TempDir Path root;

    @Test void gitMetadataAndGeneratedManifestsDoNotEnableExternalChecks() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/package.json"), "{}");
        Files.createDirectories(root.resolve("node_modules/fixture"));
        Files.writeString(root.resolve("node_modules/fixture/package.json"), "{}");
        assertThat(ExternalVerificationFixture.hasParseInput(root)).isFalse();
        assertThatThrownBy(() -> ExternalVerificationFixture.require(root, "package.json", "package.json"::equals))
                .isInstanceOf(TestAbortedException.class).hasMessageContaining(".git-only");
    }

    @Test void requiredNonemptyManifestEnablesOnlyTheMatchingCheck() throws Exception {
        Files.writeString(root.resolve("package.json"), "");
        assertThat(ExternalVerificationFixture.hasParseInput(root)).isFalse();
        Files.writeString(root.resolve("package.json"), "{\"dependencies\":{\"fixture\":\"1.0\"}}");
        assertThat(ExternalVerificationFixture.hasParseInput(root)).isTrue();
        assertThatCode(() -> ExternalVerificationFixture.require(root, "package.json", "package.json"::equals)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExternalVerificationFixture.require(root, "Gemfile.lock", "Gemfile.lock"::equals))
                .isInstanceOf(TestAbortedException.class).hasMessageContaining("Gemfile.lock");
    }
}
