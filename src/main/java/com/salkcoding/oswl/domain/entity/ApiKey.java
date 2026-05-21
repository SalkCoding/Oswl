package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * CLI 클라이언트가 서버에 데이터를 전송할 때 사용하는 API 키.
 * 프로젝트별로 발급되며, 키가 일치하지 않으면 스캔 데이터 수신이 거부된다.
 */
@Entity
@Table(name = "api_keys",
        indexes = @Index(name = "idx_api_keys_token", columnList = "token", unique = true))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** `oswl_` 프리픽스 + SecureRandom base64 토큰 */
    @Column(nullable = false, unique = true, length = 100)
    private String token;

    @Column(length = 200)
    private String label;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * CLI 인증(POST /api/cli/auth)으로 이 키를 발급한 사용자.
     * UI에서 관리자가 생성한 키는 null이다.
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    public void revoke() {
        this.active = false;
        this.revokedAt = LocalDateTime.now();
    }

    public void activate() {
        this.active = true;
        this.revokedAt = null;
    }

    public void recordUsage() {
        this.lastUsedAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return active && !isExpired();
    }
}
