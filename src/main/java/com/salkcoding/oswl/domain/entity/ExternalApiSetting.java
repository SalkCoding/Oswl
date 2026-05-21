package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 외부 API 연동 설정을 저장한다 (NVD 키, 라이브러리 캐시 TTL 등).
 * 단일 행 설정 테이블로 설계되었다 (id = 1).
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
     * CVSS 점수와 CWE ID로 CVE 데이터를 슬레요 NVD API 키.
     * null이면 NVD 강화가 비활성화되며 deps.dev의 cvss3Score를 대신 사용한다.
     */
    @Column(name = "nvd_api_key", length = 200)
    private String nvdApiKey;

    /**
     * Library 항목을 영구적으로 캐시할지 여부.
     * true면 기존 Library 행을 deps.dev/OSV에서 다시 조회하지 않는다.
     * false면 cacheTtlDays를 사용하여 구실 여부를 판단한다.
     */
    @Column(name = "permanent_cache", nullable = false)
    @Builder.Default
    private boolean permanentCache = true;

    /**
     * 캐시 TTL(일). permanentCache = false일 때만 사용된다.
     * fetchedAt이 이 값보다 오래된 Library는 재조회된다.
     */
    @Column(name = "cache_ttl_days")
    private Integer cacheTtlDays;

    /** GitHub OAuth 앱 클라이언트 ID */
    @Column(name = "github_client_id", length = 200)
    private String githubClientId;

    /** GitHub OAuth 앱 클라이언트 비밀 (클라이언트에 반환되지 않음) */
    @Column(name = "github_client_secret", length = 200)
    private String githubClientSecret;

    /** GitHub OAuth 앱에 등록된 OAuth 콜백 URL */
    @Column(name = "github_redirect_uri", length = 500)
    private String githubRedirectUri;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void updateNvdApiKey(String nvdApiKey) {
        this.nvdApiKey = nvdApiKey;
    }

    public void updateCachePolicy(boolean permanentCache, Integer cacheTtlDays) {
        this.permanentCache = permanentCache;
        this.cacheTtlDays   = cacheTtlDays;
    }

    public boolean isNvdEnabled() {
        return nvdApiKey != null && !nvdApiKey.isBlank();
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
