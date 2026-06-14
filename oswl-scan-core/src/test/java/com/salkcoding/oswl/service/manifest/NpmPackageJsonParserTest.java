package com.salkcoding.oswl.service.manifest;

import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.PARSER)
@DisplayName("NpmPackageJsonParser")
class NpmPackageJsonParserTest {

    @Test
    @DisplayName("reads dependencies and devDependencies")
    void parse_declaredDeps(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), """
                {
                  "dependencies": { "lodash": "^4.17.21" },
                  "devDependencies": { "vitest": "~1.0.0" }
                }
                """);

        var comps = NpmPackageJsonParser.parse(dir, "demo");

        assertThat(comps).hasSize(2);
        assertThat(comps).anyMatch(c -> "lodash".equals(c.getName()) && "4.17.21".equals(c.getVersion()));
        assertThat(comps).anyMatch(c -> "vitest".equals(c.getName()) && "1.0.0".equals(c.getVersion()));
    }
}
