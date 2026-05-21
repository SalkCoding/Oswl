package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ScanResult와 Library 사이의 조인 엔티티.
 * 실제 CVE와 라이선스 데이터는 공유 Library 엔티티에 있으며,
 * 스캔 컨텍스트 데이터(reviewed, ignored, dependencyInfo)는 여기에 보관한다.
 *
 * {@link DependencyPath} 행은 CLI가 전송한 전체 의존성 경로 트리를 저장하여
 * 세부 패널에서 적절한 의존성 트리를 렌더링할 수 있도록 한다.
 */
@Entity
@Table(name = "scan_components",
        indexes = {
            @Index(name = "idx_scan_components_scan", columnList = "scan_result_id"),
            @Index(name = "idx_scan_components_library", columnList = "library_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResult scanResult;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /**
     * 사람이 읽을 수 있는 의존성 요약. 예: "Direct (2) + Transitive (5)"
     * CLI가 스캔 시점에 더 빠른 표시를 위해 채운다.
     */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    // ── 유예 (예외 처리) ─────────────────────────────────────────────

    /** 유예 확정 타임스탬프; null = 유예 안 됨 */
    @Column(name = "deferred_at")
    private LocalDateTime deferredAt;

    /**
     * 사유 코드: legal-review | false-positive | wont-fix | temporary | other
     */
    @Column(name = "deferral_reason", length = 50)
    private String deferralReason;

    /** 이 유예의 만료일; null = 무기한 */
    @Column(name = "deferral_expires_at")
    private LocalDateTime deferralExpiresAt;

    /** 자유 텍스트 메모 (PR 설명 또는 'other' 사유 텍스트) */
    @Column(name = "deferral_note", columnDefinition = "TEXT")
    private String deferralNote;

    /**
     * 루트부터 이 라이브러리까지의 전체 의존성 경로 트리.
     * CLI 페이로드에서 쳄워지며, 이전 CLI 버전 스캔에는 비어 있다.
     */
    @OneToMany(mappedBy = "scanComponent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pathIndex ASC")
    @Builder.Default
    private List<DependencyPath> dependencyPaths = new ArrayList<>();

    // ── 변경 헬퍼 ─────────────────────────────────────────────────

    public void markReviewed(boolean reviewed) {
        this.reviewed = reviewed;
    }

    public void markIgnored(boolean ignored) {
        this.ignored = ignored;
    }

    public void applyDeferral(String reason, LocalDateTime expiresAt, String note) {
        this.deferredAt = LocalDateTime.now();
        this.deferralReason = reason;
        this.deferralExpiresAt = expiresAt;
        this.deferralNote = note;
    }

    public boolean isDeferred() {
        return deferredAt != null;
    }

    // ── Convenience delegates to Library ─────────────────────────────────

    public String getName()    { return library.getName(); }
    public String getVersion() { return library.getVersion(); }
}
