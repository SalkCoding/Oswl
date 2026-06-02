package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiEnrichmentContextBuilderTest {

    @Test
    @DisplayName("보안 트렌드 diff에 신규 Critical CVE가 포함된다")
    void buildSecurityTrendDetails_includesNewCritical() {
        Library prevLib = library("log4j", "2.14.0", List.of());
        Library curLib = library("log4j", "2.14.0", List.of(
                cve("CVE-2021-44228", RiskLevel.CRITICAL)));

        String details = AiEnrichmentContextBuilder.buildSecurityTrendDetails(
                List.of(curLib), List.of(prevLib), Map.of(), 1);

        assertThat(details).contains("CVE-2021-44228");
        assertThat(details).contains("New CVE instances: 1");
    }

    @Test
    @DisplayName("라이선스 트렌드 diff에 새 위험 컴포넌트가 포함된다")
    void buildLicenseTrendDetails_includesNewRestricted() {
        Library prevLib = library("safe", "1.0", LicenseStatus.PERMITTED);
        Library curLib = library("gpl-lib", "2.0", LicenseStatus.RESTRICTED);

        String details = AiEnrichmentContextBuilder.buildLicenseTrendDetails(
                List.of(curLib), List.of(prevLib), 1);

        assertThat(details).contains("gpl-lib");
        assertThat(details).contains("New license-risk components: 1");
    }

    private static Library library(String name, String version, List<Cve> cves) {
        return Library.builder().id(1L).name(name).version(version).ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED).cves(new ArrayList<>(cves)).build();
    }

    private static Library library(String name, String version, LicenseStatus status) {
        return Library.builder().id(2L).name(name).version(version).ecosystem("MAVEN")
                .licenseStatus(status).cves(new ArrayList<>()).build();
    }

    private static Cve cve(String id, RiskLevel severity) {
        return Cve.builder().cveId(id).severity(severity).cvssScore(9.0).build();
    }
}
