package com.salkcoding.oswl.domain.entity;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("Library 엔티티 단위 테스트")
class LibraryTest {

    // ── highestSeverity ───────────────────────────────────────────────────

    @Nested
    @DisplayName("highestSeverity()")
    class HighestSeverity {

        @Test
        @DisplayName("CVE가 없으면 NONE을 반환한다")
        void returnsNone_whenNoCves() {
            Library lib = lib();
            assertThat(lib.highestSeverity()).isEqualTo(RiskLevel.NONE);
        }

        @Test
        @DisplayName("CRITICAL CVE가 있으면 CRITICAL을 반환한다")
        void returnsCritical_whenCriticalExists() {
            Library lib = lib(
                    cve("CVE-1", RiskLevel.HIGH),
                    cve("CVE-2", RiskLevel.CRITICAL)
            );
            assertThat(lib.highestSeverity()).isEqualTo(RiskLevel.CRITICAL);
        }

        @Test
        @DisplayName("심각도가 null인 CVE는 무시한다")
        void ignoresNullSeverity() {
            Library lib = lib(
                    Cve.builder().cveId("CVE-X").severity(null).library(null).build(),
                    cve("CVE-1", RiskLevel.MEDIUM)
            );
            assertThat(lib.highestSeverity()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("모든 CVE의 심각도가 null이면 NONE을 반환한다")
        void returnsNone_whenAllSeveritiesNull() {
            Library lib = lib(
                    Cve.builder().cveId("CVE-X").severity(null).library(null).build()
            );
            assertThat(lib.highestSeverity()).isEqualTo(RiskLevel.NONE);
        }
    }

    // ── computePatchability ───────────────────────────────────────────────

    @Nested
    @DisplayName("computePatchability()")
    class ComputePatchability {

        @Test
        @DisplayName("CVE가 없으면 UNKNOWN을 반환한다")
        void returnsUnknown_whenNoCves() {
            assertThat(lib().computePatchability()).isEqualTo(Patchability.UNKNOWN);
        }

        @Test
        @DisplayName("모든 CVE의 severity가 NONE이면 UNKNOWN을 반환한다")
        void returnsUnknown_whenOnlyNoneSeverityCves() {
            Library lib = lib(cve("CVE-1", RiskLevel.NONE));
            assertThat(lib.computePatchability()).isEqualTo(Patchability.UNKNOWN);
        }

        @Test
        @DisplayName("fixVersion이 있는 CVE가 하나라도 있으면 PATCHABLE을 반환한다")
        void returnsPatchable_whenAnyFixVersionExists() {
            Cve withFix = Cve.builder().cveId("CVE-1").severity(RiskLevel.HIGH)
                    .fixVersion("2.0.0").library(null).build();
            Cve noFix   = Cve.builder().cveId("CVE-2").severity(RiskLevel.MEDIUM)
                    .fixVersion(null).library(null).build();
            Library lib = lib(withFix, noFix);
            assertThat(lib.computePatchability()).isEqualTo(Patchability.PATCHABLE);
        }

        @Test
        @DisplayName("모든 활성 CVE에 fixVersion이 없으면 NON_PATCHABLE을 반환한다")
        void returnsNonPatchable_whenNoFixVersionsExist() {
            Cve noFix1 = Cve.builder().cveId("CVE-1").severity(RiskLevel.HIGH)
                    .fixVersion(null).library(null).build();
            Cve noFix2 = Cve.builder().cveId("CVE-2").severity(RiskLevel.CRITICAL)
                    .fixVersion("").library(null).build();
            Library lib = lib(noFix1, noFix2);
            assertThat(lib.computePatchability()).isEqualTo(Patchability.NON_PATCHABLE);
        }
    }

    // ── bestFixVersion ────────────────────────────────────────────────────

    @Nested
    @DisplayName("bestFixVersion()")
    class BestFixVersion {

        @Test
        @DisplayName("CVE가 없으면 null을 반환한다")
        void returnsNull_whenNoCves() {
            assertThat(lib().bestFixVersion()).isNull();
        }

        @Test
        @DisplayName("fixVersion이 있는 CVE 중 심각도가 가장 높은 것의 픽스 버전을 반환한다")
        void returnsBestFixVersion_forHighestSeverity() {
            Cve critical = Cve.builder().cveId("CVE-1").severity(RiskLevel.CRITICAL)
                    .fixVersion("3.0.0").library(null).build();
            Cve medium   = Cve.builder().cveId("CVE-2").severity(RiskLevel.MEDIUM)
                    .fixVersion("2.5.0").library(null).build();
            Library lib = lib(medium, critical);
            // Should pick the fix for CRITICAL (lowest ordinal = highest severity)
            assertThat(lib.bestFixVersion()).isEqualTo("3.0.0");
        }

        @Test
        @DisplayName("fixVersion이 없는 CVE는 무시된다")
        void ignoresCvesWithoutFixVersion() {
            Cve noFix = Cve.builder().cveId("CVE-1").severity(RiskLevel.CRITICAL)
                    .fixVersion(null).library(null).build();
            Cve hasFix = Cve.builder().cveId("CVE-2").severity(RiskLevel.HIGH)
                    .fixVersion("1.9.0").library(null).build();
            Library lib = lib(noFix, hasFix);
            assertThat(lib.bestFixVersion()).isEqualTo("1.9.0");
        }

        @Test
        @DisplayName("모든 CVE에 fixVersion이 없으면 null을 반환한다")
        void returnsNull_whenNoFixVersionsExist() {
            Cve c = Cve.builder().cveId("CVE-1").severity(RiskLevel.HIGH)
                    .fixVersion(null).library(null).build();
            assertThat(lib(c).bestFixVersion()).isNull();
        }
    }

    // ── countBySeverity ───────────────────────────────────────────────────

    @Nested
    @DisplayName("countBySeverity()")
    class CountBySeverity {

        @Test
        @DisplayName("해당 심각도의 CVE 수를 정확히 반환한다")
        void countsCorrectly() {
            Library lib = lib(
                    cve("C1", RiskLevel.CRITICAL),
                    cve("C2", RiskLevel.CRITICAL),
                    cve("H1", RiskLevel.HIGH),
                    cve("M1", RiskLevel.MEDIUM)
            );
            assertThat(lib.countBySeverity("CRITICAL")).isEqualTo(2);
            assertThat(lib.countBySeverity("HIGH")).isEqualTo(1);
            assertThat(lib.countBySeverity("MEDIUM")).isEqualTo(1);
            assertThat(lib.countBySeverity("LOW")).isEqualTo(0);
        }

        @Test
        @DisplayName("대소문자 구별 없이 조회된다")
        void isCaseInsensitive() {
            Library lib = lib(cve("C1", RiskLevel.CRITICAL));
            assertThat(lib.countBySeverity("critical")).isEqualTo(1);
            assertThat(lib.countBySeverity("CRITICAL")).isEqualTo(1);
        }
    }

    // ── updateLicense / updateVersionStatus ──────────────────────────────

    @Test
    @DisplayName("updateLicense는 licenseName과 licenseStatus를 갱신한다")
    void updateLicense_updatesFields() {
        Library lib = lib();
        lib.updateLicense("MIT", LicenseStatus.PERMITTED);
        assertThat(lib.getLicenseName()).isEqualTo("MIT");
        assertThat(lib.getLicenseStatus()).isEqualTo(LicenseStatus.PERMITTED);
    }

    @Test
    @DisplayName("updateVersionStatus는 isLatestVersion, deprecated, latestVersion을 갱신한다")
    void updateVersionStatus_updatesFields() {
        Library lib = lib();
        lib.updateVersionStatus(false, "Use v2.x instead", "2.0.0");
        assertThat(lib.getIsLatestVersion()).isFalse();
        assertThat(lib.getDeprecated()).isEqualTo("Use v2.x instead");
        assertThat(lib.getLatestVersion()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("updateVersionStatus에 blank deprecated가 들어오면 null로 저장된다")
    void updateVersionStatus_storesNullForBlankDeprecated() {
        Library lib = lib();
        lib.updateVersionStatus(true, "   ", null);
        assertThat(lib.getDeprecated()).isNull();
        assertThat(lib.getLatestVersion()).isNull();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static Library lib(Cve... cves) {
        return Library.builder()
                .name("test-lib").version("1.0.0").ecosystem("MAVEN")
                .cves(List.of(cves))
                .build();
    }

    private static Cve cve(String id, RiskLevel severity) {
        return Cve.builder().cveId(id).severity(severity).library(null).build();
    }
}
