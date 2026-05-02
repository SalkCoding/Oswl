package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * CLI 클라이언트가 서버로 데이터를 전송할 때 사용하는 API 키.
 * 프로젝트 단위로 발급되며, 키가 일치하지 않으면 스캔 데이터 수신을 거부한다.
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

    /** `oswl_` 접두사 + SecureRandom base64 토큰 */
    @Column(nullable = false, unique = true, length = 100)
    private String token;

    @Column(length = 200)
    private String label;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    public void revoke() {
        this.active = false;
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
