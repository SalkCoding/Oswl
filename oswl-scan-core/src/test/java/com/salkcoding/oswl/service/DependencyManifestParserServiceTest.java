package com.salkcoding.oswl.service;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.PARSER)
@ExtendWith(MockitoExtension.class)
@DisplayName("DependencyManifestParserService")
class DependencyManifestParserServiceTest {

  private final DependencyManifestParserService parser =
      new DependencyManifestParserService(new MavenBomVersionResolver());

  @Test
  @DisplayName("parses root package.json dependencies")
  void parseDependencies_npmPackageJson(@TempDir Path dir) throws Exception {
    String json = """
        {
          "name": "demo-app",
          "dependencies": {
            "lodash": "^4.17.21"
          }
        }
        """;
    Files.writeString(dir.resolve("package.json"), json);

    DependencyManifestParserService.ParseResult result = parser.parseDependencies(dir, "demo-app");

    assertThat(result.ecosystem()).contains("NPM");
    assertThat(result.components()).anyMatch(c ->
        "lodash".equals(c.getName()) && "NPM".equals(c.getEcosystem()));
  }
}
