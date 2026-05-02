package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 스캔에서 발견된 오픈소스 컴포넌트(라이브러리).
 * 동일 컴포넌트가 여러 스캔에 걸쳐 나타날 수 있다.
 */
@Entity
@Table(name = "components",
        indexes = {
            @Index(name = "idx_components_scan", columnList = "scan_result_id"),
            @Index(name = "idx_components_name_version", columnList = "name, version")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class OswlComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResult scanResult;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 100)
    private String version;

    /** "Direct (6) + Transitive (1) · Projects (7)" 형태 표시용 */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Patchability patchability = Patchability.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", nullable = false, length = 20)
    @Builder.Default
    private LicenseStatus licenseStatus = LicenseStatus.OK;

    @Column(name = "license_name", length = 200)
    private String licenseName;

    /** AI가 생성한 라이선스 위험 요약 (nullable) */
    @Column(name = "ai_license_summary", columnDefinition = "TEXT")
    private String aiLicenseSummary;

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Cve> cves = new ArrayList<>();

    public void markReviewed(boolean reviewed) {
        this.reviewed = reviewed;
    }

    public void setAiLicenseSummary(String summary) {
        this.aiLicenseSummary = summary;
    }

    /** 보안 이슈 집계 편의 메서드 */
    public long countBySeverity(String severity) {
        return cves.stream()
                .filter(c -> c.getSeverity().name().equalsIgnoreCase(severity))
                .count();
    }
}
