package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 단일 스캔에서 나온 결과 번들.
 * CLI 버전, 스캔 시간, 상태(PENDING → COMPLETED 등)를 추적한다.
 */
@Entity
@Table(name = "scan_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** 스캔 시점의 프로젝트 버전 */
    @Column(length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScanStatus status = ScanStatus.PENDING;

    /** CLI가 전송한 원시 JSON 페이로드 (감사 목적) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    /** AI 분석 실패 시 오류 메시지 등 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** AI 생성 보안 리스크 트렌드 인사이트 (열거 중 생성) */
    @Column(name = "security_ai_insight", columnDefinition = "TEXT")
    private String securityAiInsight;

    /** AI 생성 라이선스 리스크 트렌드 인사이트 (열거 중 생성) */
    @Column(name = "license_ai_insight", columnDefinition = "TEXT")
    private String licenseAiInsight;

    /** AI 생성 보안 포스쳒 인사이트 - 현재 CVE 수 요약 (열거 중 생성) */
    @Column(name = "security_posture_insight", columnDefinition = "TEXT")
    private String securityPostureInsight;

    /**
     * 이 스캔을 제출한 사용자 (Quick Import 또는 사용자도 CLI 인증 이후).
     * 프로젝트 API 키로 제출된 익명 CLI 스캔은 null이다.
     */
    @Column(name = "submitted_by_user_id")
    private Long submittedByUserId;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @PrePersist
    private void initScannedAt() {
        if (this.scannedAt == null) {
            this.scannedAt = LocalDateTime.now();
        }
    }

    /** DataInitializer 등에서 테스트 데이터 주입 시 사용 */
    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }

    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanComponent> components = new ArrayList<>();

    public void complete() {
        this.status = ScanStatus.COMPLETED;
    }

    public void updateAiInsights(String securityInsight, String licenseInsight) {
        this.securityAiInsight = securityInsight;
        this.licenseAiInsight  = licenseInsight;
    }

    public void updateSecurityPostureInsight(String insight) {
        this.securityPostureInsight = insight;
    }

    public void startAnalyzing() {
        this.status = ScanStatus.ANALYZING;
    }

    public void startScanning() {
        this.status = ScanStatus.SCANNING;
    }

    public void fail(String message) {
        this.status = ScanStatus.FAILED;
        this.errorMessage = message;
    }

    /**
     * 동일 버전 재스캔을 위해 스캔을 초기화한다.
     * 이전 페이로드/상태를 지워서 새 컴포넌트 데이터를 새로 수신할 수 있도록 한다.
     * 호출자는 이 메서드를 호출하기 전에 components 콜렉션을 비워야 한다.
     */
    public void resetForRescan(String newRawPayload) {
        this.rawPayload = newRawPayload;
        this.status = ScanStatus.PENDING;
        this.errorMessage = null;
        this.scannedAt = LocalDateTime.now();
    }
}
