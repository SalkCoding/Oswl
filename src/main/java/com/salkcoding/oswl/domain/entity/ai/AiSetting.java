package com.salkcoding.oswl.domain.entity.ai;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI provider settings.
 * Stored in this table when an operator injects an API key and provider from the UI.
 * The LOCAL provider only needs a baseUrl and may have no apiKey.
 *
 * ⚠️ It is recommended to store the apiKey column encrypted
 *    (consider JasyptStringEncryptor or Vault integration in production).
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
    @Column(nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private AiProvider provider;

    /**
     * OpenAI / Anthropic: API key — stored AES-256-GCM encrypted.
     * LOCAL: null allowed (e.g. Ollama with no auth).
     * Column length 1000 to accommodate Base64-encoded ciphertext.
     */
    @Column(name = "api_key", length = 1000)
    private String apiKey;

    /**
     * Model name (e.g. "gpt-4o", "claude-3-5-sonnet-20241022", "llama3")
     */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /**
     * Endpoint for the LOCAL provider (e.g. "http://localhost:11434/v1")
     * For local LLMs using an OpenAI-compatible API, only this URL needs to be changed.
     */
    @Column(name = "base_url", length = 300)
    private String baseUrl;

    /** Whether this is the currently active provider (only one can be active at a time) */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    /**
     * True when this LOCAL row is the built-in llama.cpp sidecar rather than a user-configured
     * endpoint. Embedded AI publishes itself as the LOCAL provider, so without this flag the
     * settings page cannot tell "built-in model" from "external Ollama" — both showed as LOCAL and
     * appeared selected at once. Recorded on the row instead of asking the sidecar process, so the
     * distinction survives a restart with the sidecar stopped. Null on rows written before this
     * column existed and is read as false.
     */
    @Column(name = "embedded_managed")
    private Boolean embeddedManaged;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Null-safe view of {@link #embeddedManaged} — absent means a user-configured endpoint. */
    public boolean isEmbeddedManaged() {
        return Boolean.TRUE.equals(embeddedManaged);
    }

    public void markEmbeddedManaged(boolean embeddedManaged) {
        this.embeddedManaged = embeddedManaged;
    }

    public void update(String apiKey, String modelName, String baseUrl) {
        if (apiKey != null) this.apiKey = apiKey;
        if (modelName != null) this.modelName = modelName;
        if (baseUrl != null) this.baseUrl = baseUrl;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
