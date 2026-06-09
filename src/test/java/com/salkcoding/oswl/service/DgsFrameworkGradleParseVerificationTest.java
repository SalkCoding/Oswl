package com.salkcoding.oswl.service;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional verification against a local shallow clone of Netflix/dgs-framework.
 * Clone path: {@code %TEMP%/oswl-verify/Netflix_dgs-framework}
 */
@DisplayName("Gradle BOM verification — dgs-framework clone")
class DgsFrameworkGradleParseVerificationTest {

  private static final Path DGS_CLONE = Path.of(
      System.getProperty("java.io.tmpdir"), "oswl-verify", "Netflix_dgs-framework");

  @Test
  @DisplayName("static Gradle+BOM parse resolves versions for majority of declared deps")
  void staticParse_resolvesMostVersions() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(DGS_CLONE),
        "Skip: clone dgs-framework to " + DGS_CLONE);

    MavenBomVersionResolver resolver = new MavenBomVersionResolver();
    List<ScanPayload.ComponentPayload> comps = resolver.parseGradleDeclaredWithBom(DGS_CLONE);

    assertThat(comps).isNotEmpty();

    long withVersion = comps.stream()
        .filter(c -> c.getVersion() != null && !c.getVersion().isBlank())
        .count();
    double ratio = (double) withVersion / comps.size();
    System.out.printf("[GradleVerify] dgs-framework: %d/%d components with version (%.0f%%)%n",
        withVersion, comps.size(), ratio * 100);

    // Before Kotlin DSL BOM variable support this was ~10% (7/68); expect a large jump.
    assertThat(ratio)
        .as("components with resolved version (%d / %d)", withVersion, comps.size())
        .isGreaterThan(0.5);
  }
}
