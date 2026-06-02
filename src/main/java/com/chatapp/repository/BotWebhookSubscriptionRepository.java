package com.chatapp.repository;

import com.chatapp.entity.BotWebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotWebhookSubscriptionRepository extends JpaRepository<BotWebhookSubscription, Long> {

    List<BotWebhookSubscription> findByBotConfigIdAndIsActiveTrue(Long botConfigId);

    List<BotWebhookSubscription> findByBotConfigId(Long botConfigId);
}
