package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CveCvssEvidenceTest {
    @ParameterizedTest
    @CsvSource({
            "7.5,new-vector,7.5,new-vector",
            "7.5,,7.5,",
            ",new-vector,,new-vector",
            ",,9.8,prior-vector",
            "7.5,' ',7.5,"
    })
    void refreshDoesNotCombineDifferentRevisions(Double score, String vector,
                                                 Double expectedScore, String expectedVector) {
        var cve = Cve.builder().cvssScore(9.8).cvss3Vector("prior-vector").build();
        cve.enrichFromAdvisory(null, null, score, vector, null);
        assertThat(cve.getCvssScore()).isEqualTo(expectedScore);
        assertThat(cve.getCvss3Vector()).isEqualTo(expectedVector);
    }

    @ParameterizedTest
    @CsvSource({
            "9.8,,9.8,",
            ",prior-vector,,prior-vector",
            "9.8,prior-vector,9.8,prior-vector",
            ",,7.5,new-vector"
    })
    void enrichmentDoesNotCombineDifferentSources(Double score, String vector,
                                                 Double expectedScore, String expectedVector) {
        var cve = Cve.builder().cvssScore(score).cvss3Vector(vector).build();
        cve.setCvssIfMissing(7.5, "new-vector");
        assertThat(cve.getCvssScore()).isEqualTo(expectedScore);
        assertThat(cve.getCvss3Vector()).isEqualTo(expectedVector);
    }
}
