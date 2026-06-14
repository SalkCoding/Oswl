package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ApiKeyType;
import com.salkcoding.oswl.service.ApiKeyTokenSupport;
import com.salkcoding.oswl.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * API key used by the CLI client when sending data to the server.
 * The full token is never stored — only a lookup prefix and BCrypt hash.
 */
@Entity
@Table(name = "api_keys",
        indexes = @Index(name = "idx_api_keys_token_prefix", columnList = "token_prefix", unique = true))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** First {@link com.salkcoding.oswl.service.ApiKeyTokenSupport#PREFIX_LENGTH} chars of the token for lookup */
    @Column(name = "token_prefix", nullable = false, unique = true, length = 20)
    private String tokenPrefix;

    /** BCrypt hash of the full token (includes {@code oswl_} prefix) */
    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    @Column(length = 200)
    private String label;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false, length = 20)
    @Builder.Default
    private ApiKeyType keyType = ApiKeyType.STANDARD;

    /** For MACHINE keys — user whose SCAN_SUBMIT permission and project membership are used */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bound_user_id")
    private User boundUser;

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

    public void applyTokenHash(String plainToken, String tokenHash) {
        this.tokenPrefix = ApiKeyTokenSupport.extractPrefix(plainToken);
        this.tokenHash = tokenHash;
    }

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

    public boolean isMachineToken() {
        return keyType == ApiKeyType.MACHINE;
    }
}
