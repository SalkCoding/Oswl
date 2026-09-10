package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class NuGetVersionComparatorTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/version-oracles/nuget.csv", numLinesToSkip = 1)
    void agreesWithNuGet790(String left, String right, int expected) {
        assertThat(Integer.signum(NuGetVersionComparator.compare(left, right))).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.*", "[1,2)", "1..0", "1.0.0.0.0", "2147483648.0.0",
            "1.0.0-01", "1.0.0-alpha.01", "1.0.0+", "1.0.0-", "v1.0.0"})
    void invalidConcreteVersionsStayUnresolved(String version) {
        assertThatThrownBy(() -> NuGetVersionComparator.compare(version, "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
