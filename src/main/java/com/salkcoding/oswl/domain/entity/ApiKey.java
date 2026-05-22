package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * API key used by the CLI client when sending data to the server.
 * Issued per project, and scan data is rejected if the key does not match.
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

    /** `oswl_` prefix + SecureRandom base64 token */
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
     * User who issued this key through CLI authentication (POST /api/cli/auth).
     * Keys created by an administrator in the UI are null.
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
