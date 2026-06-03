package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "provider_credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_provider_credential_owner_label",
                columnNames = {"owner_id", "label"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "encryptedSecret")
public class ProviderCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_provider", nullable = false, length = 40)
    private BotConfig.LLMProvider llmProvider;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    @Column(name = "secret_fingerprint", nullable = false, length = 64)
    private String secretFingerprint;

    @Column(name = "secret_last4", length = 12)
    private String secretLast4;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    private String memo;

    /** Optional OpenAI-compatible endpoint override (e.g. OpenRouter / dashscope-proxy / Ollama). */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /** Optional default model for this key, used when a bot does not set its own model name. */
    @Column(name = "model_override", length = 120)
    private String modelOverride;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
