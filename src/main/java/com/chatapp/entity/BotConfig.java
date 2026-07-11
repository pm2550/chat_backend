package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "character_card_json", columnDefinition = "JSON")
    private String characterCardJson;

    @Column(name = "character_persona", columnDefinition = "TEXT")
    private String characterPersona;

    @Column(name = "character_scenario", columnDefinition = "TEXT")
    private String characterScenario;

    @Column(name = "character_first_mes", columnDefinition = "TEXT")
    private String characterFirstMes;

    @Column(name = "character_mes_example", columnDefinition = "TEXT")
    private String characterMesExample;

    @Column(name = "character_creator_notes", columnDefinition = "TEXT")
    private String characterCreatorNotes;

    @Column(name = "character_system_prompt", columnDefinition = "TEXT")
    private String characterSystemPrompt;

    @Column(name = "character_post_history_instructions", columnDefinition = "TEXT")
    private String characterPostHistoryInstructions;

    @Column(name = "character_alternate_greetings", columnDefinition = "JSON")
    private String characterAlternateGreetings;

    @Column(name = "character_book_json", columnDefinition = "JSON")
    private String characterBookJson;

    @Column(name = "temperature")
    private Double temperature = 0.7;

    @Column(name = "max_tokens")
    private Integer maxTokens = 2048;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reply_mode", nullable = false, length = 32)
    private ReplyMode replyMode = ReplyMode.SINGLE;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "workflow_mode", nullable = false, length = 32)
    private WorkflowMode workflowMode = WorkflowMode.SINGLE_PASS;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "image_generation_provider", nullable = false, length = 32)
    private ImageGenerationProvider imageGenerationProvider = ImageGenerationProvider.HERMES;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_provider_credential_id")
    private ProviderCredential imageProviderCredential;

    @Column(name = "image_model", length = 120)
    private String imageModel;

    @Column(name = "image_negative_prompt", columnDefinition = "TEXT")
    private String imageNegativePrompt;

    @Column(name = "max_history_messages")
    private Integer maxHistoryMessages = 20;

    @Column(name = "include_room_metadata")
    private Boolean includeRoomMetadata = true;

    @Column(name = "include_memory_section")
    private Boolean includeMemorySection = true;

    @Column(name = "system_prompt_template", columnDefinition = "TEXT")
    private String systemPromptTemplate;

    @Column(name = "max_context_tokens_estimate")
    private Integer maxContextTokensEstimate = 6000;

    @Column(name = "enabled_tools", columnDefinition = "TEXT")
    private String enabledTools;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "access_policy", nullable = false, length = 32)
    private AccessPolicy accessPolicy = AccessPolicy.PRIVATE;

    // Inbound bot token (Phase 4 / F1): lets an external service post AS this bot.
    // Only fingerprint + last4 are stored; the raw token is shown once on rotate.
    @Column(name = "inbound_token_fingerprint", length = 64)
    private String inboundTokenFingerprint;

    @Column(name = "inbound_token_last4", length = 8)
    private String inboundTokenLast4;

    @Column(name = "inbound_token_scopes", columnDefinition = "TEXT")
    private String inboundTokenScopes;

    @Column(name = "max_agent_iterations")
    private Integer maxAgentIterations = 8;

    @Column(name = "max_agent_wallclock_ms")
    private Integer maxAgentWallclockMs = 30000;

    @Column(name = "max_agent_total_tokens")
    private Integer maxAgentTotalTokens = 50000;

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
        OPENAI, CLAUDE, DEEPSEEK, OLLAMA, HERMES, DASHSCOPE, KIMI, IMAGE_API, NOVELAI
    }

    public enum AccessPolicy {
        /** Only the owner can install and trigger the bot. */
        PRIVATE,
        /** Owner plus explicitly allowed users can install and trigger the bot. */
        ALLOWLIST,
        /** Room admins can install it and room members can trigger it after install. */
        PUBLIC
    }

    public enum ReplyMode {
        /** Current behavior: send the whole LLM answer as one chat bubble. */
        SINGLE,
        /** Human-like behavior: split a plain text answer into sentence bubbles. */
        CHUNKED
    }

    public enum WorkflowMode {
        /** Current behavior: one context build and one LLM call. */
        SINGLE_PASS,
        /** Kirara-style two-pass flow: infer context first, then speak in persona. */
        KIRARA_TWO_PASS
    }

    public enum ImageGenerationProvider {
        /** PM chat's managed Hermes /draw service. */
        HERMES,
        /** A user-supplied OpenAI-compatible /images/generations endpoint. */
        OPENAI_COMPATIBLE,
        /** NovelAI's official /ai/generate-image API. */
        NOVELAI
    }
}
