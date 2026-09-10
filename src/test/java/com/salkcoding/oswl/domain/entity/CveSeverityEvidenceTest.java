package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.enums.CveSource;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CveSeverityEvidenceTest {
    @ParameterizedTest
    @CsvSource({
            "OSV,CRITICAL,HIGH,CRITICAL,true",
            "NVD,HIGH,CRITICAL,CRITICAL,true",
            "GITHUB_ADVISORY,HIGH,HIGH,HIGH,false",
            "OSV,HIGH,,HIGH,false",
            "DEPS_DEV,CRITICAL,HIGH,HIGH,false",
            "DEPS_DEV,HIGH,CRITICAL,CRITICAL,false",
            "DEPS_DEV,HIGH,,HIGH,false",
            ",CRITICAL,HIGH,CRITICAL,true"
    })
    void advisoryRefreshPreservesOtherSourceEvidence(CveSource previousSource, RiskLevel previous,
                                                    RiskLevel incoming, RiskLevel expected, boolean conflict) {
        var cve = Cve.builder().severity(previous)
                .sources(new HashSet<>(previousSource == null ? Set.of() : Set.of(previousSource))).build();
        cve.enrichFromAdvisory("CVE-2026-0001", "Updated title", null, null, incoming);
        assertThat(cve.getSeverity()).isEqualTo(expected);
        assertThat(cve.isSeverityConflict()).isEqualTo(conflict);
        assertThat(cve.getTitle()).isEqualTo("Updated title");
    }
}
