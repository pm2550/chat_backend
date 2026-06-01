package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "feature_costs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeatureCost {

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    @Column(name = "cost_points", nullable = false)
    private Integer costPoints;

    @Column(name = "free_daily_quota", nullable = false)
    private Integer freeDailyQuota = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String description;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
