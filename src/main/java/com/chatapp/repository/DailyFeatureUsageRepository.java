package com.chatapp.repository;

import com.chatapp.entity.DailyFeatureUsage;
import com.chatapp.entity.DailyFeatureUsageId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyFeatureUsageRepository extends JpaRepository<DailyFeatureUsage, DailyFeatureUsageId> {
    Optional<DailyFeatureUsage> findByUserIdAndFeatureKeyAndUsageDate(Long userId, String featureKey, LocalDate usageDate);

    List<DailyFeatureUsage> findByUserIdAndUsageDate(Long userId, LocalDate usageDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from DailyFeatureUsage u
            where u.userId = :userId and u.featureKey = :featureKey and u.usageDate = :usageDate
            """)
    Optional<DailyFeatureUsage> findLockedByUserIdAndFeatureKeyAndUsageDate(
            @Param("userId") Long userId,
            @Param("featureKey") String featureKey,
            @Param("usageDate") LocalDate usageDate);
}
