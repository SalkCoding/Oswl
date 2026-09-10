package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pep440VersionComparatorTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/version-oracles/pep440.csv", numLinesToSkip = 1)
    void agreesWithPackaging262(String left, String right, int comparison) {
        assertThat(Integer.signum(Pep440VersionComparator.compare(left, right))).isEqualTo(comparison);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.*", ">=1.0", "1..0", "1.0+", "1.0-", "1.0+abc..1", "1.0.post1a1", "1.0unknown"})
    void invalidInputIsNotAssignedAnOrder(String version) {
        assertThatThrownBy(() -> Pep440VersionComparator.compare(version, "1.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
