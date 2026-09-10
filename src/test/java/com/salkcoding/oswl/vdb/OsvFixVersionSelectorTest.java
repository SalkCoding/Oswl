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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commonFixIsCheckedAgainstEveryAdvisoryRegardlessOfOrder(boolean reverse) {
        var first = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))));
        var second = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "3.0.0"))));
        var input = reverse ? List.of(second, first) : List.of(first, second);
        assertThat(OsvFixVersionSelector.selectAcrossAdvisories(input, "npm", "target", "1.0.0").version()).isEqualTo("3.0.0");
        // A later affected interval makes even the larger individual fix unsafe.
        var reintroduced = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"),
                Map.of("fixed", "2.0.0"), Map.of("introduced", "2.5.0"))));
        input = reverse ? List.of(second, reintroduced) : List.of(reintroduced, second);
        assertThat(OsvFixVersionSelector.selectAcrossAdvisories(input, "npm", "target", "1.0.0").version()).isNull();
        var fixedAgain = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"),
                Map.of("fixed", "2.0.0"), Map.of("introduced", "2.5.0"), Map.of("fixed", "4.0.0"))));
        input = reverse ? List.of(second, fixedAgain) : List.of(fixedAgain, second);
        assertThat(OsvFixVersionSelector.selectAcrossAdvisories(input, "npm", "target", "1.0.0").version()).isEqualTo("4.0.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unsupported", "unfixed", "foreign", "withdrawn"})
    void incompleteEvidenceCannotAuthorizeACommonFix(String state) {
        var known = document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))));
        JsonNode other = switch (state) {
            case "missing" -> mapper.nullNode();
            case "unsupported" -> document(entry("target", "GIT", List.of(Map.of("introduced", "0"))));
            case "unfixed" -> document(entry("target", "SEMVER", List.of(Map.of("introduced", "0"))));
            case "foreign" -> document(entry("other", "SEMVER", List.of(Map.of("introduced", "0"), Map.of("fixed", "2.0.0"))));
            default -> ((com.fasterxml.jackson.databind.node.ObjectNode) known.deepCopy()).put("withdrawn", "2026-01-01T00:00:00Z");
        };
        assertThat(OsvFixVersionSelector.selectAcrossAdvisories(List.of(known, other), "npm", "target", "1.0.0").version()).isNull();
        assertThat(OsvFixVersionSelector.selectAcrossAdvisories(List.of(), "npm", "target", "1.0.0").version()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "{}", "[1]", "[null]", "[\"\"]", "[\" \"]"})
    void malformedVersionListsCannotConfirmAFix(String versions) throws Exception {
        var advisory = mapper.readTree("""
                {"affected":[{"package":{"ecosystem":"npm","name":"target"},
                "versions":%s,"ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(versions));
        assertThat(select(advisory, "1.0.0").version()).isNull();
        assertThat(select(advisory, "1.0.0").reason()).isEqualTo("MALFORMED_VERSIONS");
    }

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
