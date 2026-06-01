package com.chatapp.repository;

import com.chatapp.entity.PointsLedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PointsLedgerRepository extends JpaRepository<PointsLedgerEntry, Long> {
    List<PointsLedgerEntry> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<PointsLedgerEntry> findByUserIdAndRefKeyAndRefIdOrderByCreatedAtDesc(Long userId, String refKey, String refId);

    boolean existsByUserIdAndRefKeyAndRefIdAndReason(
            Long userId,
            String refKey,
            String refId,
            PointsLedgerEntry.LedgerReason reason);

    Optional<PointsLedgerEntry> findFirstByUserIdAndRefKeyAndRefIdAndReasonOrderByCreatedAtDesc(
            Long userId,
            String refKey,
            String refId,
            PointsLedgerEntry.LedgerReason reason);
}
