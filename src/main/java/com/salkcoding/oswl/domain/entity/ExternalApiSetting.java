package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Stores external API integration settings (library cache TTL, GitHub OAuth, etc.).
 * Designed as a single-row settings table (id = 1).
 */
@Entity
@Table(name = "external_api_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ExternalApiSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Whether to cache Library entries permanently.
     * If true, existing Library rows are not re-fetched from deps.dev/OSV.
     * If false, staleness is determined using cacheTtlDays.
     */
    @Column(name = "permanent_cache", nullable = false)
    @Builder.Default
    private boolean permanentCache = true;

    /**
     * Cache TTL in days. Used only when permanentCache = false.
     * Libraries whose fetchedAt is older than this value are re-fetched.
     */
    @Column(name = "cache_ttl_days")
    private Integer cacheTtlDays;

    /** GitHub OAuth app client ID */
    @Column(name = "github_client_id", length = 200)
    private String githubClientId;

    /** GitHub OAuth app client secret (never returned to the client) */
    @Column(name = "github_client_secret", length = 200)
    private String githubClientSecret;

    /** OAuth callback URL registered for the GitHub OAuth app */
    @Column(name = "github_redirect_uri", length = 500)
    private String githubRedirectUri;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void updateCachePolicy(boolean permanentCache, Integer cacheTtlDays) {
        this.permanentCache = permanentCache;
        this.cacheTtlDays   = cacheTtlDays;
    }

    public boolean isGithubConfigured() {
        return githubClientId != null && !githubClientId.isBlank()
                && githubClientSecret != null && !githubClientSecret.isBlank();
    }

    public void updateGithubOAuth(String clientId, String clientSecret, String redirectUri) {
        if (clientId != null)     this.githubClientId     = clientId.isBlank()     ? null : clientId;
        if (clientSecret != null) this.githubClientSecret = clientSecret.isBlank() ? null : clientSecret;
        if (redirectUri != null)  this.githubRedirectUri  = redirectUri.isBlank()  ? null : redirectUri;
    }
}
