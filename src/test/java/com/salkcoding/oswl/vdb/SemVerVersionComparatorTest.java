package com.salkcoding.oswl.vdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemVerVersionComparatorTest {
    @ParameterizedTest
    @CsvSource({"1.0.0+1, 1.0.0+2, 0", "1.0.0-alpha, 1.0.0, -1", "1.9.0, 1.10.0, -1",
            "1.0.0-2, 1.0.0-10, -1", "1.0.0-10, 1.0.0-alpha, -1",
            "1.0.0-alpha, 1.0.0-alpha.1, -1", "1.0.0-alpha+001, 1.0.0-alpha+002, 0",
            "99999999999999999999.0.0, 100000000000000000000.0.0, -1"})
    void followsSemVerPrecedence(String a, String b, int expected) {
        assertThat(Integer.signum(SemVerVersionComparator.compare(a, b))).isEqualTo(expected);
        assertThat(Integer.signum(SemVerVersionComparator.compare(b, a))).isEqualTo(-expected);
    }

    @Test
    void ordersTheSpecificationsPrereleaseSequence() {
        var versions = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        for (int i = 0; i < versions.size(); i++) {
            for (int j = i + 1; j < versions.size(); j++) {
                assertThat(SemVerVersionComparator.compare(versions.get(i), versions.get(j))).isNegative();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.2", "v1.2.3", "01.2.3", "1.2.3-01", "1.2.3-",
            "1.2.3+", "1.2.3-alpha..1", "1.2.3.4", "1.2.3~rc1", "1.2.3 "})
    void rejectsOtherVersionGrammars(String version) {
        assertThatThrownBy(() -> SemVerVersionComparator.compare(version, "1.2.3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyIdentifiersDoNotExhaustTheRegexStack() {
        String version = "1.0.0-" + "a.".repeat(1500) + "a";
        assertThat(SemVerVersionComparator.compare(version, version)).isZero();
        assertThatThrownBy(() -> SemVerVersionComparator.compare("1.0.0+" + "a".repeat(4096), "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
