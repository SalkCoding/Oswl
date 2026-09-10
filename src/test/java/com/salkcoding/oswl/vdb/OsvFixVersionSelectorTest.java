package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OsvFixVersionSelectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ignoresFixesForOtherPackages() {
        var document = document(entry("other", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "99.0.0"))),
                entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))));
        assertThat(select(document, "1.0.0").version()).isEqualTo("2.0.0");
        assertThat(select(document, "2.0.0").version()).isNull();
    }

    @Test
    void doesNotTreatLastAffectedOrLimitAsAFix() {
        for (String boundary : List.of("last_affected", "limit")) {
            assertThat(select(document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of(boundary, "2.0.0")))),
                    "1.0.0").version()).isNull();
        }
    }

    @Test
    void openLaterIntervalDoesNotReuseEarlierFix() {
        var document = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "1.0.0"),
                Map.of("introduced", "2.0.0"))));
        assertThat(select(document, "2.1.0").version()).isNull();
    }

    @Test
    void rejectsAFixStillAffectedByAnotherEntry() {
        var document = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))),
                entry("target", "SEMVER", List.of(Map.of("introduced", "1.0.0"))));
        assertThat(select(document, "1.5.0").reason()).isEqualTo("FIX_CONFLICTS_WITH_AFFECTED_DATA");
        assertThat(select(document, "1.5.0").version()).isNull();
    }

    @Test
    void unsupportedRangesCannotAuthorizeAFixFromASupportedRange() {
        var document = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))),
                entry("target", "GIT", List.of(Map.of("introduced", "0"))));
        assertThat(select(document, "1.0.0").reason()).isEqualTo("UNSUPPORTED_RANGE");
        assertThat(select(document, "1.0.0").version()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "version-unknown"})
    void invalidInstalledVersionsDoNotReceiveGuessedFixes(String installed) {
        assertThat(select(document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0")))),
                installed).version()).isNull();
    }

    @Test
    void malformedEventsDoNotYieldUnverifiedFixes() {
        assertThat(select(document(entry("target", "SEMVER", List.of(Map.of("fixed", "2.0.0")))), "1.0.0").version()).isNull();
    }

    @Test
    void withdrawnAdvisoryDoesNotSupplyAFix() {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) document(entry("target", "SEMVER",
                List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))));
        document.put("withdrawn", "2026-01-01T00:00:00Z");
        assertThat(select(document, "1.0.0").reason()).isEqualTo("WITHDRAWN");
        assertThat(select(document, "1.0.0").version()).isNull();
    }

    private OsvFixVersionSelector.Selection select(JsonNode document, String installed) {
        return OsvFixVersionSelector.select(document, "npm", "target", installed);
    }

    private JsonNode document(Map<?, ?>... entries) { return mapper.valueToTree(Map.of("affected", List.of(entries))); }

    private Map<String, Object> entry(String name, String type, List<?> events) {
        return Map.of("package", Map.of("ecosystem", "npm", "name", name), "ranges", List.of(Map.of("type", type, "events", events)));
    }
}
