package com.salkcoding.oswl.service.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloneRootPathGuard")
class CloneRootPathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("파일이 clone root 밖이면 verifyContained 실패")
    void verifyContained_outsideRoot_throws() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("clone"));
        Path outside = Files.createFile(tempDir.resolve("outside.txt"));
        CloneRootPathGuard guard = new CloneRootPathGuard(root);

        assertThatThrownBy(() -> guard.verifyContained(outside))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("root 하위 파일은 허용")
    void verifyContained_insideRoot_ok() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("clone"));
        Path inside = Files.createFile(root.resolve("pom.xml"));
        CloneRootPathGuard guard = new CloneRootPathGuard(root);

        assertThat(guard.verifyContained(inside)).startsWith(guard.root());
    }
}
