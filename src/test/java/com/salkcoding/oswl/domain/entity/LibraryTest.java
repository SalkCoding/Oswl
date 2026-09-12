package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Library 엔티티 단위 테스트")
class LibraryTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"missing,true", "missing,false", "failed,true", "failed,false", "complete,true", "complete,false"})
    void patchabilityRequiresCompletedLookupWithoutErasingIndividualFixes(String state, boolean hasFix) {
        var finding = Cve.builder().cveId("CVE-2026-123450").severity(RiskLevel.HIGH)
                .fixVersion(hasFix ? "2.0.0" : null).build();
        var library = lib(finding);
        if (!state.equals("missing")) library.recordLookupOutcomes(java.util.Map.of("OSV","RESOLVED",
                "DEPS_DEV",state.equals("failed") ? "UNAVAILABLE" : "RESOLVED"));
        library.markFetched();
        assertThat(library.computePatchability()).isEqualTo(state.equals("complete")
                ? hasFix ? Patchability.PATCHABLE : Patchability.NON_PATCHABLE : Patchability.UNKNOWN);
        assertThat(finding.getFixVersion()).isEqualTo(hasFix ? "2.0.0" : null);
        assertThat(library.getCves()).containsExactly(finding);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"complete", "missing", "uncovered", "conflict", "expired", "partial"})
    void recommendationUsesCompleteCurrentCommonEvidenceInsteadOfSeverity(String state) {
        var first = Cve.builder().ghsaId("OSV-first").severity(RiskLevel.CRITICAL).fixVersion("2.0.0").build();
        var second = Cve.builder().ghsaId("OSV-second").severity(RiskLevel.HIGH).fixVersion("3.0.0").build();
        if (state.equals("conflict")) second.withholdFixVersion(java.util.Set.of("3.0.0", "5.0.0"));
        String expiry = java.time.Instant.now().plusSeconds(state.equals("expired") ? -60 : 3600).toString();
        String json = """
                {"version":"4.0.0","reason":"SOURCE_FIXED_EVENT",
                "advisoryRevisions":{"OSV-first":"2026-01-01T00:00:00Z","OSV-second":"2026-01-01T00:00:00Z"},
                "findingIds":["OSV-first"%s],"validUntil":"%s"}
                """.formatted(state.equals("uncovered") ? "" : ",\"OSV-second\"", expiry);
        var library = Library.builder().name("example").version("1.0.0").ecosystem("NPM")
                .cves(java.util.List.of(first, second)).latestVersion("99.0.0").isLatestVersion(false)
                .fetchedAt(java.time.LocalDateTime.now()).vulnerabilityLookupAt(java.time.LocalDateTime.now())
                .vulnerabilityLookupOutcomes(java.util.Map.of("OSV", "RESOLVED", "GITHUB_ADVISORY", state.equals("partial") ? "UNAVAILABLE" : "NOT_CONFIGURED"))
                .osvFixAssessment(state.equals("missing") ? null : new Library.OsvFixAssessmentConverter().convertToEntityAttribute(json)).build();
        assertThat(library.bestFixVersion()).isEqualTo(state.equals("complete") ? "4.0.0" : null);
        assertThat(library.resolvePrTargetVersion()).isEqualTo(state.equals("complete") ? "4.0.0" : null);
        assertThat(first.getFixVersion()).isEqualTo("2.0.0");
        assertThat(second.getFixVersion()).isEqualTo(state.equals("conflict") ? null : "3.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "conflict", "current"})
    void unresolvedSecurityFixDoesNotFallBackToLatestForPr(String state) {
        Cve finding = Cve.builder().severity(RiskLevel.HIGH)
                .fixVersion(state.equals("current") ? "1.0.0" : null).build();
        if (state.equals("conflict")) finding.getFixVersionConflictCandidates().addAll(List.of("2.0.0", "3.0.0"));
        Library library = Library.builder().version("1.0.0").latestVersion("9.0.0")
                .isLatestVersion(false).cves(List.of(finding)).build();
        assertThat(library.resolvePrTargetVersion()).isNull();
    }

    @Test void maintenancePrRequiresConfirmedOutdatedStatus() {
        Library unknown = Library.builder().version("1.0.0").latestVersion("9.0.0")
                .isLatestVersion(null).cves(List.of()).build();
        Library outdated = Library.builder().version("1.0.0").latestVersion("9.0.0")
                .isLatestVersion(false).cves(List.of()).build();
        assertThat(unknown.resolvePrTargetVersion()).isNull();
        assertThat(outdated.resolvePrTargetVersion()).isEqualTo("9.0.0");
    }

    @Test void knownFixRemainsThePrTargetInsteadOfLatest() {
        Cve finding = Cve.builder().ghsaId("OSV-fixture").severity(RiskLevel.HIGH).fixVersion("2.0.0").build();
        Library library = Library.builder().version("1.0.0").latestVersion("9.0.0")
                .isLatestVersion(false).cves(List.of(finding)).build();
        library.markFetched();
        library.recordLookupOutcomes(java.util.Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", java.util.Map.of("OSV-fixture", "2026-01-01T00:00:00Z"),
                java.util.Set.of("OSV-fixture"), java.time.Instant.now().plusSeconds(3600));
        assertThat(library.resolvePrTargetVersion()).isEqualTo("2.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NVD", "CPE", "CONFIDENCE", "NO_FIX", "MIXED"})
    void cpeReviewWithholdsPatchabilityAndCachedRecommendation(String state) {
        var sources = state.equals("CPE") ? java.util.Set.of(com.salkcoding.oswl.domain.enums.CveSource.CPE)
                : state.equals("CONFIDENCE") ? java.util.Set.<com.salkcoding.oswl.domain.enums.CveSource>of()
                : java.util.Set.of(com.salkcoding.oswl.domain.enums.CveSource.NVD);
        var candidate = Cve.builder().cveId("CVE-2026-123450").severity(RiskLevel.HIGH).sources(sources)
                .matchConfidence(com.salkcoding.oswl.domain.enums.MatchConfidence.HIGH)
                .fixVersion(state.equals("NO_FIX") ? null : "2.0.0").build();
        var findings = new java.util.ArrayList<Cve>();
        findings.add(candidate);
        if (state.equals("MIXED")) findings.add(Cve.builder().cveId("CVE-2026-123451")
                .severity(RiskLevel.HIGH).sources(java.util.Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV))
                .fixVersion("2.0.0").build());
        var library = Library.builder().version("1.0.0").latestVersion("9.0.0").isLatestVersion(false).cves(findings).build();
        library.markFetched();
        library.recordLookupOutcomes(java.util.Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", java.util.Map.of("fixture", "2026-01-01T00:00:00Z"),
                java.util.Set.of("CVE-2026-123450", "CVE-2026-123451"), java.time.Instant.now().plusSeconds(3600));
        assertThat(library.computePatchability()).isEqualTo(Patchability.UNKNOWN);
        assertThat(library.bestFixVersion()).isNull();
        assertThat(library.resolvePrTargetVersion()).isNull();
        assertThat(candidate.getFixVersion()).isEqualTo(state.equals("NO_FIX") ? null : "2.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.salkcoding.oswl.domain.enums.CveSource.class,
            names = {"OSV", "DEPS_DEV", "GITHUB_ADVISORY"})
    void packageEvidenceKeepsVerifiedCommonRecommendation(com.salkcoding.oswl.domain.enums.CveSource source) {
        var finding = Cve.builder().cveId("CVE-2026-123450").severity(RiskLevel.HIGH).fixVersion("2.0.0")
                .sources(java.util.Set.of(source, com.salkcoding.oswl.domain.enums.CveSource.NVD))
                .matchConfidence(com.salkcoding.oswl.domain.enums.MatchConfidence.LOW).build();
        var library = Library.builder().version("1.0.0").cves(List.of(finding)).build();
        library.markFetched();
        library.recordLookupOutcomes(java.util.Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", java.util.Map.of("fixture", "2026-01-01T00:00:00Z"),
                java.util.Set.of("CVE-2026-123450"), java.time.Instant.now().plusSeconds(3600));
        assertThat(library.computePatchability()).isEqualTo(Patchability.PATCHABLE);
        assertThat(library.bestFixVersion()).isEqualTo("2.0.0");
        assertThat(library.resolvePrTargetVersion()).isEqualTo("2.0.0");
    }

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
        private Library analyzed(Cve... findings) {
            Library library = lib(findings);
            library.recordLookupOutcomes(java.util.Map.of("OSV","RESOLVED"));
            library.markFetched();
            return library;
        }


        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
        void anUnscoredFindingStillContributesItsKnownFix(boolean includeScoredFinding) {
            var unscored = Cve.builder().cveId("CVE-2026-0001").severity(null).fixVersion("2.0.0").build();
            var library = includeScoredFinding ? analyzed(unscored, cve("CVE-2026-0002", RiskLevel.HIGH)) : analyzed(unscored);
            assertThat(library.computePatchability()).isEqualTo(Patchability.PATCHABLE);
            assertThat(library.bestFixVersion()).isNull();
            assertThat(unscored.getFixVersion()).isEqualTo("2.0.0");
        }

        @Test
        @DisplayName("CVE가 없으면 UNKNOWN을 반환한다")
        void returnsUnknown_whenNoCves() {
            assertThat(analyzed().computePatchability()).isEqualTo(Patchability.UNKNOWN);
        }

        @Test
        @DisplayName("모든 CVE의 severity가 NONE이면 UNKNOWN을 반환한다")
        void returnsUnknown_whenOnlyNoneSeverityCves() {
            Library lib = analyzed(cve("CVE-1", RiskLevel.NONE));
            assertThat(lib.computePatchability()).isEqualTo(Patchability.UNKNOWN);
        }

        @Test
        @DisplayName("fixVersion이 있는 CVE가 하나라도 있으면 PATCHABLE을 반환한다")
        void returnsPatchable_whenAnyFixVersionExists() {
            Cve withFix = Cve.builder().cveId("CVE-1").severity(RiskLevel.HIGH)
                    .fixVersion("2.0.0").library(null).build();
            Cve noFix   = Cve.builder().cveId("CVE-2").severity(RiskLevel.MEDIUM)
                    .fixVersion(null).library(null).build();
            Library lib = analyzed(withFix, noFix);
            assertThat(lib.computePatchability()).isEqualTo(Patchability.PATCHABLE);
        }

        @Test
        @DisplayName("모든 활성 CVE에 fixVersion이 없으면 NON_PATCHABLE을 반환한다")
        void returnsNonPatchable_whenNoFixVersionsExist() {
            Cve noFix1 = Cve.builder().cveId("CVE-1").severity(RiskLevel.HIGH)
                    .fixVersion(null).library(null).build();
            Cve noFix2 = Cve.builder().cveId("CVE-2").severity(RiskLevel.CRITICAL)
                    .fixVersion("").library(null).build();
            Library lib = analyzed(noFix1, noFix2);
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
        @DisplayName("심각도와 개별 수정 버전만으로 공통 후보를 확정하지 않는다")
        void severityDoesNotEstablishACommonCandidate() {
            Cve critical = Cve.builder().cveId("CVE-1").severity(RiskLevel.CRITICAL)
                    .fixVersion("3.0.0").library(null).build();
            Cve medium   = Cve.builder().cveId("CVE-2").severity(RiskLevel.MEDIUM)
                    .fixVersion("2.5.0").library(null).build();
            Library lib = lib(medium, critical);
            assertThat(lib.bestFixVersion()).isNull();
            assertThat(critical.getFixVersion()).isEqualTo("3.0.0");
            assertThat(medium.getFixVersion()).isEqualTo("2.5.0");
        }

        @Test
        @DisplayName("수정 근거가 없는 CVE를 공통 후보 선택에서 누락하지 않는다")
        void missingFixEvidenceCannotBeIgnored() {
            Cve noFix = Cve.builder().cveId("CVE-1").severity(RiskLevel.CRITICAL)
                    .fixVersion(null).library(null).build();
            Cve hasFix = Cve.builder().cveId("CVE-2").severity(RiskLevel.HIGH)
                    .fixVersion("1.9.0").library(null).build();
            Library lib = lib(noFix, hasFix);
            assertThat(lib.bestFixVersion()).isNull();
            assertThat(hasFix.getFixVersion()).isEqualTo("1.9.0");
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
