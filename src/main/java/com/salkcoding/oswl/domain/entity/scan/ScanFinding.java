package com.salkcoding.oswl.domain.entity.scan;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A secret or IaC misconfiguration finding from a Quick Import clone.
 *
 * The matched secret value itself is never persisted — only the file location, the
 * rule that matched, and a fingerprint (hash prefix) so the same finding can be
 * recognized as unchanged across scans without being able to reconstruct the secret.
 */
@Entity
@Table(name = "scan_findings", indexes = {
        @Index(name = "idx_scan_findings_scan_result", columnList = "scan_result_id"),
        @Index(name = "idx_scan_findings_scan_result_type", columnList = "scan_result_id, type")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResult scanResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScanFindingType type;

    /** Stable identifier of the rule that matched, e.g. {@code aws-access-key}, {@code tf-public-s3}. */
    @Column(name = "rule_id", nullable = false, length = 100)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel severity;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    /** Short human-readable description of what was found — never includes the matched value. */
    @Column(nullable = false, length = 500)
    private String description;

    /**
     * First 12 hex chars of SHA-256(matched value), so the same secret re-appearing across
     * scans can be recognized without storing (or being able to recover) the value itself.
     * Null for IaC findings, which have no secret value to fingerprint.
     */
    @Column(length = 12)
    private String fingerprint;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
