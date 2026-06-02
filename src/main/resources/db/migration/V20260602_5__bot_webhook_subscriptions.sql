-- Phase 4 (F1) Slice 2: outbound webhooks. When a non-bot message matches a bot
-- that has an active webhook subscription, the room event is HMAC-signed and POSTed
-- to the external callback_url (the owner's OpenClaw / TG / QQ bridge), which then
-- replies via the inbound bot-gateway (Slice 1). No enum columns (per the
-- content_format crash lesson) — event_types is a comma-separated varchar.
CREATE TABLE IF NOT EXISTS `bot_webhook_subscriptions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bot_config_id` bigint NOT NULL,
  `chat_room_id` bigint DEFAULT NULL,
  `callback_url` varchar(1000) NOT NULL,
  `secret_encrypted` text DEFAULT NULL,
  `event_types` varchar(255) NOT NULL DEFAULT 'message',
  `is_active` bit(1) NOT NULL DEFAULT b'1',
  `consecutive_failures` int NOT NULL DEFAULT 0,
  `last_delivery_status` int DEFAULT NULL,
  `last_delivery_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_bot_webhook_bot_active` (`bot_config_id`, `is_active`),
  CONSTRAINT `fk_bot_webhook_bot` FOREIGN KEY (`bot_config_id`)
    REFERENCES `bot_configs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
