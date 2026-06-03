package com.chatapp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyFeatureUsageId implements Serializable {
    private Long userId;
    private String featureKey;
    private LocalDate usageDate;
}
