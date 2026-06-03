package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "redemption_codes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedemptionCode {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(nullable = false)
    private Integer points;

    @Column(name = "batch_label", length = 80)
    private String batchLabel;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column(name = "issued_by_user_id", nullable = false)
    private Long issuedByUserId;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "redeemed_by_user_id")
    private Long redeemedByUserId;

    @Column(name = "redeemed_at")
    private LocalDateTime redeemedAt;
}
