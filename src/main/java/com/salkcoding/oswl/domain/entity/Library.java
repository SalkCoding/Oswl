package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * (name, version, ecosystem)으로 식별되는 고유한 오픈소스 라이브러리.
 * CVE 및 라이선스 데이터는 모든 프로젝트/스캔에 공유되므로 여기에 한 번만 저장된다.
 */
@Entity
@Table(name = "libraries",
        indexes = {
            @Index(name = "idx_libraries_name_version", columnList = "name, version")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_library_name_version_ecosystem",
                columnNames = {"name", "version", "ecosystem"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Library {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 100)
    private String version;

    /**
     * deps.dev system 파라미터에 사용되는 패키지 시스템.
     * 예: MAVEN, NPM, PYPI, GO, CARGO, NUGET, RUBYGEMS
     */
    @Column(nullable = false, length = 20)
    private String ecosystem;

    /** deps.dev가 반환하는 기본 SPDX 라이선스 표현식 */
    @Column(name = "license_name", length = 200)
    private String licenseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", length = 20)
    @Builder.Default
    private LicenseStatus licenseStatus = LicenseStatus.UNKNOWN;

    /**
     * deps.dev가 이 패키지의 기본(latest stable) 버전으로 보고할 때 true.
     * null은 정보가 아직 조회되지 않았음을 의미한다.
     */
    @Column(name = "is_latest_version")
    private Boolean isLatestVersion;

    /**
     * deps.dev가 이 버전을 사용 중단으로 표시할 때 비널.
     * deps.dev의 사용 중단 이유 문자열을 포함한다.
     */
    @Column(name = "deprecated", length = 500)
    private String deprecated;

    /**
     * deps.dev에서 가져온 최신 stable 버전 문자열.
     * 현재 버전이 최신이 아닐 때만 채워지며, 이미 최신이면 null이다.
     */
    @Column(name = "latest_version", length = 100)
    private String latestVersion;

    /** 마지막으로 성공한 deps.dev + OSV 조회 타임스탬프 */
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /** 라이브러리 라이선스에 대한 AI 생성 한 문장 컴플라이언스 리스크 요약 (열거 중 생성) */
    @Column(name = "ai_license_summary", columnDefinition = "TEXT")
    private String aiLicenseSummary;

    @OneToMany(mappedBy = "library", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Cve> cves = new ArrayList<>();

    // ── 변경 헬퍼 ──────────────────────────────────────────────────

    public void updateLicense(String licenseName, LicenseStatus licenseStatus) {
        this.licenseName = licenseName;
        this.licenseStatus = licenseStatus;
    }

    public void updateVersionStatus(boolean isLatestVersion, String deprecated, String latestVersion) {
        this.isLatestVersion = isLatestVersion;
        this.deprecated = (deprecated != null && !deprecated.isBlank()) ? deprecated : null;
        this.latestVersion = (latestVersion != null && !latestVersion.isBlank()) ? latestVersion : null;
    }

    public void markFetched() {
        this.fetchedAt = LocalDateTime.now();
    }

    public void updateAiLicenseSummary(String summary) {
        this.aiLicenseSummary = summary;
    }

    // ── 계산 프로퍼티 ──────────────────────────────────────────────

    public long countBySeverity(String severity) {
        return cves.stream()
                .filter(c -> c.getSeverity() != null && c.getSeverity().name().equalsIgnoreCase(severity))
                .count();
    }

    /**
     * CVE 픽스 버전으로 도출 가능 여부를 계산한다:
     * - CVE 없음 → UNKNOWN
     * - 하나라도 fixVersion 있음 → PATCHABLE
     * - 모든 CVE에 fixVersion 없음 → NON_PATCHABLE
     */
    public Patchability computePatchability() {
        List<Cve> activeCves = cves.stream()
                .filter(c -> c.getSeverity() != null && c.getSeverity() != RiskLevel.NONE)
                .toList();
        if (activeCves.isEmpty()) return Patchability.UNKNOWN;
        boolean anyFixed = activeCves.stream()
                .anyMatch(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank());
        return anyFixed ? Patchability.PATCHABLE : Patchability.NON_PATCHABLE;
    }

    /**
     * 이 라이브러리의 최고 심각도 CVE (CRITICAL > HIGH > MEDIUM > LOW > 점수 없음).
     */
    public RiskLevel highestSeverity() {
        return cves.stream()
                .map(Cve::getSeverity)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(RiskLevel.NONE);
    }

    /** 최적 픽스 버전 — 가장 심각도가 높은 CVE 중 픽스 버전이 있는 것에서 가져온다 */
    public String bestFixVersion() {
        return cves.stream()
                .filter(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank())
                .min(Comparator.comparingInt(c -> c.getSeverity() != null ? c.getSeverity().ordinal() : 999))
                .map(Cve::getFixVersion)
                .orElse(null);
    }
}
