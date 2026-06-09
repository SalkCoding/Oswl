package com.salkcoding.oswl.service.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code static/scripts/manifest-rules.json} in sync with {@link ManifestCollectRules}.
 */
@DisplayName("manifest-rules.json sync")
class ManifestRulesJsonSyncTest {

    private static final Path RESOURCE = Path.of(
            "src/main/resources/static/scripts/manifest-rules.json");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("committed manifest-rules.json matches ManifestCollectRules")
    void jsonMatchesJavaSourceOfTruth() throws Exception {
        assertThat(RESOURCE).exists();
        ManifestCollectRules.RulesJson fromFile =
                mapper.readValue(Files.readString(RESOURCE), ManifestCollectRules.RulesJson.class);
        ManifestCollectRules.RulesJson current = ManifestCollectRules.RulesJson.current();

        assertThat(fromFile).isEqualTo(current);
    }
}
