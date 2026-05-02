package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 클라이언트가 올린 1회 스캔의 결과 묶음.
 * CLI 버전, 스캔 시각, 상태(PENDING→COMPLETED 등)를 추적한다.
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

    /** 스캔 당시의 프로젝트 버전 */
    @Column(length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScanStatus status = ScanStatus.PENDING;

    /** CLI가 보낸 원시 JSON 페이로드 (감사 목적) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    /** AI 분석 실패 등 에러 메시지 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "scanned_at", updatable = false)
    private LocalDateTime scannedAt;

    /** DataInitializer 등에서 테스트 데이터 주입 시 사용 */
    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }

    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OswlComponent> components = new ArrayList<>();

    public void complete() {
        this.status = ScanStatus.COMPLETED;
    }

    public void fail(String message) {
        this.status = ScanStatus.FAILED;
        this.errorMessage = message;
    }
}
