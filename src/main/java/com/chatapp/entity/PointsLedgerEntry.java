package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "points_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ledger_user_reason_ref",
                columnNames = {"user_id", "reason", "ref_key", "ref_id"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Integer delta;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private LedgerReason reason;

    @Column(name = "ref_key", length = 60)
    private String refKey;

    @Column(name = "ref_id", length = 120)
    private String refId;

    @Column(name = "balance_paid_after", nullable = false)
    private Integer balancePaidAfter;

    @Column(name = "free_used", nullable = false)
    private Integer freeUsed = 0;

    @Column(name = "free_remaining_after")
    private Integer freeRemainingAfter;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum LedgerReason {
        DAILY_GRANT,
        FEATURE_DEBIT,
        FEATURE_REFUND,
        REDEEM_CODE,
        ADMIN_CREDIT,
        ADMIN_DEBIT
    }
}
