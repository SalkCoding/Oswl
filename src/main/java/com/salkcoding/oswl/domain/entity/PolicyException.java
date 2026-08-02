package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A requested, approved, revoked, or expired waiver for a specific gate finding
 * (or an entire component). Approved exceptions suppress matching violations
 * during gate evaluation until they expire.
 */
@Entity
@Table(name = "policy_exceptions",
        indexes = {
                @Index(name = "idx_policy_exceptions_project_status",
                        columnList = "project_id, status"),
                @Index(name = "idx_policy_exceptions_status_expiry",
                        columnList = "status, expiry")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class PolicyException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "requester_name", length = 100)
    private String requesterName;

    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "approver_name", length = 100)
    private String approverName;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime expiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PolicyExceptionStatus status = PolicyExceptionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    @Builder.Default
    private PolicyExceptionTargetType targetType = PolicyExceptionTargetType.ALL;

    @Column(name = "target_id", length = 200)
    private String targetId;

    @Column(name = "component_coordinate", length = 300)
    private String componentCoordinate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public void approve(Long approverUserId, String approverName) {
        if (this.status != PolicyExceptionStatus.PENDING) {
            throw new IllegalStateException("Only pending exceptions can be approved.");
        }
        this.status = PolicyExceptionStatus.APPROVED;
        this.approverUserId = approverUserId;
        this.approverName = approverName;
        this.approvedAt = LocalDateTime.now();
    }

    public void revoke() {
        if (this.status == PolicyExceptionStatus.EXPIRED) {
            throw new IllegalStateException("Expired exceptions cannot be revoked.");
        }
        this.status = PolicyExceptionStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

    public void expire() {
        if (this.status == PolicyExceptionStatus.APPROVED) {
            this.status = PolicyExceptionStatus.EXPIRED;
        }
    }

    public boolean isApproved() {
        return this.status == PolicyExceptionStatus.APPROVED;
    }
}
