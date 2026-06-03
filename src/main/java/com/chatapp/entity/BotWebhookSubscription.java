package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Outbound webhook subscription for a bot (Phase 4 / F1 Slice 2). When a room event
 * matches the bot, it is HMAC-signed and POSTed to {@code callbackUrl}. {@code chatRoomId}
 * null = all rooms the bot is bound to.
 */
@Entity
@Table(name = "bot_webhook_subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "secretEncrypted")
public class BotWebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bot_config_id", nullable = false)
    private BotConfig botConfig;

    @Column(name = "chat_room_id")
    private Long chatRoomId;

    @Column(name = "callback_url", nullable = false, length = 1000)
    private String callbackUrl;

    @Column(name = "secret_encrypted", columnDefinition = "TEXT")
    private String secretEncrypted;

    @Column(name = "event_types", nullable = false, length = 255)
    private String eventTypes = "message";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    @Column(name = "last_delivery_status")
    private Integer lastDeliveryStatus;

    @Column(name = "last_delivery_at")
    private LocalDateTime lastDeliveryAt;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
