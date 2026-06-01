package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "bot_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "apiKeyEncrypted")
public class BotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bot_name", nullable = false, length = 100)
    private String botName;

    @Column(name = "bot_avatar", length = 500)
    private String botAvatar;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_provider", nullable = false)
    private LLMProvider llmProvider;

    @Column(name = "api_key_encrypted", length = 500)
    private String apiKeyEncrypted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_credential_id")
    private ProviderCredential providerCredential;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "temperature")
    private Double temperature = 0.7;

    @Column(name = "max_tokens")
    private Integer maxTokens = 2048;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum LLMProvider {
        OPENAI, CLAUDE, DEEPSEEK, OLLAMA, HERMES
    }
}
