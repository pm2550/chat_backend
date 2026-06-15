package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "web_push_subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_web_push_endpoint_hash", columnNames = "endpoint_hash"),
        indexes = @Index(name = "idx_web_push_user_active", columnList = "user_id,is_active")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebPushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "endpoint", nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    @Column(name = "endpoint_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String endpointHash;

    @Column(name = "p256dh", nullable = false, length = 512)
    private String p256dh;

    @Column(name = "auth", nullable = false, length = 255)
    private String auth;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
