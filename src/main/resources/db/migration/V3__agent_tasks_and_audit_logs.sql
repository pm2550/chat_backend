CREATE TABLE `agent_tasks` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `result` text COLLATE utf8mb4_unicode_ci,
  `error_message` text COLLATE utf8mb4_unicode_ci,
  `status` enum('PENDING','RUNNING','SUCCEEDED','FAILED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `chat_room_id` bigint NOT NULL,
  `requested_by_id` bigint NOT NULL,
  `bot_config_id` bigint DEFAULT NULL,
  `result_message_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_tasks_chat_room_created` (`chat_room_id`, `created_at`),
  KEY `idx_agent_tasks_requested_by` (`requested_by_id`),
  KEY `idx_agent_tasks_bot_config` (`bot_config_id`),
  KEY `idx_agent_tasks_result_message` (`result_message_id`),
  CONSTRAINT `fk_agent_tasks_chat_room` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `fk_agent_tasks_requested_by` FOREIGN KEY (`requested_by_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_agent_tasks_bot_config` FOREIGN KEY (`bot_config_id`) REFERENCES `bot_configs` (`id`),
  CONSTRAINT `fk_agent_tasks_result_message` FOREIGN KEY (`result_message_id`) REFERENCES `messages` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `audit_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor_id` bigint DEFAULT NULL,
  `actor_username` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `action` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `resource_type` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `resource_id` bigint DEFAULT NULL,
  `chat_room_id` bigint DEFAULT NULL,
  `detail` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_logs_created_at` (`created_at`),
  KEY `idx_audit_logs_chat_room_created` (`chat_room_id`, `created_at`),
  KEY `idx_audit_logs_actor` (`actor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
