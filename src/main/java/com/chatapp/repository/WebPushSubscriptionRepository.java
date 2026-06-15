package com.chatapp.repository;

import com.chatapp.entity.WebPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebPushSubscriptionRepository extends JpaRepository<WebPushSubscription, Long> {
    List<WebPushSubscription> findByUserIdAndIsActiveTrue(Long userId);

    Optional<WebPushSubscription> findByEndpointHash(String endpointHash);

    Optional<WebPushSubscription> findByUserIdAndEndpointHash(Long userId, String endpointHash);
}
