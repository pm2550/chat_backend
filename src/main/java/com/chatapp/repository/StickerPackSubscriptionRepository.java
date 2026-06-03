package com.chatapp.repository;

import com.chatapp.entity.StickerPackSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StickerPackSubscriptionRepository extends JpaRepository<StickerPackSubscription, Long> {
    Optional<StickerPackSubscription> findByPackIdAndUserId(Long packId, Long userId);
    void deleteByPackIdAndUserId(Long packId, Long userId);
}
