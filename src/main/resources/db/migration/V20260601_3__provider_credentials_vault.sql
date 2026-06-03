CREATE TABLE IF NOT EXISTS `provider_credentials` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `llm_provider` varchar(40) NOT NULL,
  `label` varchar(120) NOT NULL,
  `encrypted_secret` text NOT NULL,
  `secret_fingerprint` varchar(64) NOT NULL,
  `secret_last4` varchar(12) DEFAULT NULL,
  `is_active` bit(1) NOT NULL DEFAULT b'1',
  `memo` text DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_credential_owner_label` (`owner_id`, `label`),
  KEY `idx_provider_credentials_owner_provider` (`owner_id`, `llm_provider`, `is_active`),
  CONSTRAINT `fk_provider_credentials_owner`
    FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `bot_configs`
  ADD COLUMN `provider_credential_id` bigint DEFAULT NULL AFTER `api_key_encrypted`,
  ADD KEY `idx_bot_configs_provider_credential` (`provider_credential_id`),
  ADD CONSTRAINT `fk_bot_configs_provider_credential`
    FOREIGN KEY (`provider_credential_id`) REFERENCES `provider_credentials` (`id`)
    ON DELETE SET NULL;
