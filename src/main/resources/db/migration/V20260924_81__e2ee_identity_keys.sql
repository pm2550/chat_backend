-- 私聊端到端加密：每个用户的长期 X25519 身份密钥（可有多个版本）和加密开关。
-- 私钥只以客户端用登录密码派生的密钥包装后的密文存放（wrapped_private_key），服务器解不开。
-- 旧的 user_key_bundles 表是之前"假加密"留下的，新客户端不再使用，这里不动它。
CREATE TABLE `e2ee_identity_keys` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `key_version` int NOT NULL,
  `public_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `wrapped_private_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `wrap_salt` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `wrap_params` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_e2ee_identity_keys_user_version` (`user_id`, `key_version`),
  CONSTRAINT `fk_e2ee_identity_keys_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `e2ee_user_states` (
  `user_id` bigint NOT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'0',
  `active_key_version` int DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_e2ee_user_states_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
