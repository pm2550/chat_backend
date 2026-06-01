package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "daily_feature_usage")
@IdClass(DailyFeatureUsageId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyFeatureUsage {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "feature_key", length = 60)
    private String featureKey;

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "count", nullable = false)
    private Integer count = 0;
}
