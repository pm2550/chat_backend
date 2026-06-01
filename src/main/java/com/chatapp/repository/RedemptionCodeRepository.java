package com.chatapp.repository;

import com.chatapp.entity.RedemptionCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RedemptionCodeRepository extends JpaRepository<RedemptionCode, String> {
    Optional<RedemptionCode> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RedemptionCode c where c.codeHash = :codeHash")
    Optional<RedemptionCode> findLockedByCodeHash(@Param("codeHash") String codeHash);

    List<RedemptionCode> findAllByRedeemedAtIsNullAndExpiresAtBefore(LocalDateTime expiresAt);

    @Query("""
            select c from RedemptionCode c
            where (:batchLabel is null or c.batchLabel = :batchLabel)
              and (:status = 'all'
                or (:status = 'unused' and c.redeemedAt is null and (c.expiresAt is null or c.expiresAt > :now))
                or (:status = 'redeemed' and c.redeemedAt is not null)
                or (:status = 'expired' and c.redeemedAt is null and c.expiresAt is not null and c.expiresAt <= :now))
            order by c.issuedAt desc
            """)
    List<RedemptionCode> searchCodes(
            @Param("status") String status,
            @Param("batchLabel") String batchLabel,
            @Param("now") LocalDateTime now,
            Pageable pageable);
}
