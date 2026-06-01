ALTER TABLE `messages`
  ADD COLUMN `bot_config_id` bigint DEFAULT NULL AFTER `sender_id`,
  ADD KEY `idx_messages_bot_config` (`bot_config_id`),
  ADD CONSTRAINT `fk_messages_bot_config`
    FOREIGN KEY (`bot_config_id`) REFERENCES `bot_configs` (`id`);
