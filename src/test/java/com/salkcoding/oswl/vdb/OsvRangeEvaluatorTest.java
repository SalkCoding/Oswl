package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static com.salkcoding.oswl.vdb.OsvRangeEvaluator.Result.*;
import static org.assertj.core.api.Assertions.assertThat;

class OsvRangeEvaluatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"1.0.0,1.0,AFFECTED", "1.0RC1,1.0rc1,AFFECTED", "1.0rev1,1.0.post1,AFFECTED",
            "1.0+vendor.1,1.0,NOT_AFFECTED", "1.0,1.0.post1,NOT_AFFECTED", "1.0,invalid,UNKNOWN"})
    void pypiEnumeratedVersionsRespectVersionIdentity(String installed, String listed, OsvRangeEvaluator.Result expected) {
        assertThat(OsvRangeEvaluator.evaluate("PYPI", installed, Set.of(listed), null)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0.5.0,AFFECTED", "1.0.0,NOT_AFFECTED", "1.5.0,NOT_AFFECTED",
            "2.0.0,AFFECTED", "2.9.0,AFFECTED", "3.0.0,NOT_AFFECTED"})
    void reconstructsEveryInterval(String version, OsvRangeEvaluator.Result expected) throws Exception {
        var ranges = range("SEMVER", "[{\"introduced\":\"0\"},{\"fixed\":\"1.0.0\"},{\"introduced\":\"2.0.0\"},{\"fixed\":\"3.0.0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", version, null, ranges)).isEqualTo(expected);
    }

    @Test
    void enumeratedVersionsAndRangesAreAUnion() throws Exception {
        var ranges = range("SEMVER", "[{\"introduced\":\"1.0.0\"},{\"fixed\":\"2.0.0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "1.5.0", Set.of("0.9.0"), ranges)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "0.9.0", Set.of("0.9.0"), ranges)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "2.0.0", Set.of("0.9.0"), ranges)).isEqualTo(NOT_AFFECTED);
    }

    @Test
    void lastAffectedIsInclusiveAndOpenIntervalsRemainAffected() throws Exception {
        var closed = range("SEMVER", "[{\"introduced\":\"0\"},{\"last_affected\":\"2.0.0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "2.0.0", null, closed)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "2.0.1", null, closed)).isEqualTo(NOT_AFFECTED);
        var open = range("SEMVER", "[{\"introduced\":\"2.0.0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "99.0.0", null, open)).isEqualTo(AFFECTED);
    }

    @Test
    void limitsAreExclusiveAndMultipleLimitsExpandTheScope() throws Exception {
        var ranges = range("SEMVER", "[{\"introduced\":\"0\"},{\"limit\":\"1.0.0\"},{\"limit\":\"3.0.0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "2.0.0", null, ranges)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "3.0.0", null, ranges)).isEqualTo(NOT_AFFECTED);
        var infinity = range("SEMVER", "[{\"introduced\":\"0\"},{\"limit\":\"*\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "99.0.0", null, infinity)).isEqualTo(AFFECTED);
    }

    @Test
    void sortsSourceEventsAndIgnoresBuildMetadata() throws Exception {
        var ranges = range("SEMVER", "[{\"fixed\":\"2.0.0+1\"},{\"introduced\":\"1.0.0-alpha\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "1.0.0", null, ranges)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "2.0.0+2", null, ranges)).isEqualTo(NOT_AFFECTED);
    }

    @Test
    void unsupportedRangesRequireEvidenceInsteadOfStringOrdering() throws Exception {
        var git = range("GIT", "[{\"introduced\":\"0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NPM", "abc123", null, git)).isEqualTo(UNKNOWN);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "abc123", Set.of("abc123"), git)).isEqualTo(AFFECTED);
        var ecosystem = range("ECOSYSTEM", "[{\"introduced\":\"0\"}]");
        assertThat(OsvRangeEvaluator.evaluate("NUGET", "1.0", Set.of("0.9"), ecosystem)).isEqualTo(UNKNOWN);
        assertThat(OsvRangeEvaluator.evaluate("PYPI", "1.0", Set.of("0.9"), ecosystem)).isEqualTo(AFFECTED);
    }

    @Test
    void alpineUsesApkOrdering() throws Exception {
        var ranges = range("ECOSYSTEM", "[{\"introduced\":\"0\"},{\"fixed\":\"1.2.3-r2\"}]");
        assertThat(OsvRangeEvaluator.evaluate("ALPINE:3.20", "1.2.3-r1", null, ranges)).isEqualTo(AFFECTED);
        assertThat(OsvRangeEvaluator.evaluate("ALPINE:3.20", "1.2.3-r2", null, ranges)).isEqualTo(NOT_AFFECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"fixed\":\"1.0.0\"}]", "[{\"introduced\":null}]",
            "[{\"introduced\":\"0\",\"fixed\":\"1.0.0\"}]",
            "[{\"introduced\":\"0\"},{\"fixed\":\"1.0.0\"},{\"last_affected\":\"2.0.0\"}]"})
    void malformedRangesAreUnknown(String events) throws Exception {
        assertThat(OsvRangeEvaluator.evaluate("NPM", "1.0.0", null, range("SEMVER", events))).isEqualTo(UNKNOWN);
    }

    @Test
    void absentEvidenceAndInvalidVersionsAreUnknown() throws Exception {
        assertThat(OsvRangeEvaluator.evaluate("NPM", "1.0.0", null, JSON.readTree("[]"))).isEqualTo(UNKNOWN);
        assertThat(OsvRangeEvaluator.evaluate("NPM", "not-a-version", null,
                range("SEMVER", "[{\"introduced\":\"0\"}]"))).isEqualTo(UNKNOWN);
    }

    private JsonNode range(String type, String events) throws Exception {
        return JSON.readTree("[{\"type\":\"" + type + "\",\"events\":" + events + "}]");
    }
}
