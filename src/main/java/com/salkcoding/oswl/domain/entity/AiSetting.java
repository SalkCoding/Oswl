package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 제공자 설정.
 * 운영자가 UI에서 API 키와 제공자를 주입하면 이 테이블에 저장된다.
 * LOCAL 제공자는 baseUrl만 필요하고 apiKey가 없을 수 있다.
 *
 * ⚠️ apiKey 컬럼은 암호화 저장을 권장한다
 *    (운영 환경에서는 JasyptStringEncryptor 또는 Vault 연동 고려).
 */
@Entity
@Table(name = "ai_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AiSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    /**
     * OpenAI / Anthropic: API 키
     * LOCAL: null 허용 (인증 없는 Ollama 등)
     */
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /**
     * 모델명 (예: "gpt-4o", "claude-3-5-sonnet-20241022", "llama3")
     */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /**
     * LOCAL 제공자용 엔드포인트 (예: "http://localhost:11434/v1")
     * OpenAI 호환 API를 사용하는 로컬 LLM이라면 이 URL만 변경하면 된다.
     */
    @Column(name = "base_url", length = 300)
    private String baseUrl;

    /** 현재 활성화된 제공자인지 여부 (하나만 활성화 가능) */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void update(String apiKey, String modelName, String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.baseUrl = baseUrl;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
