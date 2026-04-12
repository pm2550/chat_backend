-- Generated from Hibernate-derived schema (entity sources of truth)
-- Re-generate via: mysqldump -d chatapp | sed 's/AUTO_INCREMENT=[0-9]*//g'

SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE `anonymous_identities` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anonymous_avatar` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `anonymous_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assigned_date` date NOT NULL,
  `custom_name_used` bit(1) DEFAULT NULL,
  `chat_room_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK7q2gkqh79lgb7kjuovislq5us` (`user_id`,`chat_room_id`,`assigned_date`),
  KEY `FKrnbqn29qxsft7n3hksmcsr8rt` (`chat_room_id`),
  CONSTRAINT `FKloaan8el1hg4m9lhqttcjntbx` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKrnbqn29qxsft7n3hksmcsr8rt` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `bot_configs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `api_key_encrypted` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bot_avatar` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bot_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `llm_provider` enum('OPENAI','CLAUDE','DEEPSEEK','OLLAMA') COLLATE utf8mb4_unicode_ci NOT NULL,
  `max_tokens` int DEFAULT NULL,
  `model_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `system_prompt` text COLLATE utf8mb4_unicode_ci,
  `temperature` double DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK1jugvn0cfjiv6ikdccq5wcysu` (`created_by`),
  CONSTRAINT `FK1jugvn0cfjiv6ikdccq5wcysu` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chat_room_id` bigint DEFAULT NULL,
  `content` text COLLATE utf8mb4_unicode_ci,
  `deleted_at` datetime(6) DEFAULT NULL,
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `file_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_deleted` bit(1) DEFAULT NULL,
  `is_recalled` bit(1) DEFAULT NULL,
  `message_type` enum('TEXT','IMAGE','FILE','AUDIO','VIDEO','SYSTEM') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `recalled_at` datetime(6) DEFAULT NULL,
  `receiver_id` bigint DEFAULT NULL,
  `reply_to_id` bigint DEFAULT NULL,
  `sender_avatar` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sender_id` bigint NOT NULL,
  `sender_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_room_bots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `added_at` datetime(6) DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `trigger_keywords` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `trigger_mode` enum('MENTION','KEYWORD','ALL') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bot_config_id` bigint NOT NULL,
  `chat_room_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKpqbsnw4wb1kc7up9pyfm4rl8j` (`chat_room_id`,`bot_config_id`),
  KEY `FKlm3wd78l4fwysxpsgk76vg5pd` (`bot_config_id`),
  CONSTRAINT `FKlm3wd78l4fwysxpsgk76vg5pd` FOREIGN KEY (`bot_config_id`) REFERENCES `bot_configs` (`id`),
  CONSTRAINT `FKooupf631elpyskwyu99gpfy4s` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_room_members` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `is_admin` bit(1) DEFAULT NULL,
  `is_muted` bit(1) DEFAULT NULL,
  `joined_at` datetime(6) DEFAULT NULL,
  `last_read_message_id` bigint DEFAULT NULL,
  `member_role` enum('OWNER','ADMIN','MODERATOR','MEMBER') COLLATE utf8mb4_unicode_ci NOT NULL,
  `nickname` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `unread_count` int DEFAULT NULL,
  `chat_room_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKpal8hkw0sfg71i5v05l0ojhq1` (`user_id`,`chat_room_id`),
  KEY `FK6x7kwk21yt5odfxv5ubbrmo0h` (`chat_room_id`),
  CONSTRAINT `FK6x7kwk21yt5odfxv5ubbrmo0h` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `FKbemsjj4g0iny4xpkvj5rwj6ab` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `chat_rooms` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `anonymous_enabled` bit(1) DEFAULT NULL,
  `anonymous_theme` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `avatar_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `is_active` bit(1) DEFAULT NULL,
  `is_private` bit(1) DEFAULT NULL,
  `max_members` int DEFAULT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_type` enum('PRIVATE','GROUP','CHANNEL','PUBLIC') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKin9277aywbjursj2b4e3bmw3s` (`created_by`),
  CONSTRAINT `FKin9277aywbjursj2b4e3bmw3s` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `device_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `device_info` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `platform` enum('ANDROID','IOS','WEB','WINDOWS','MACOS','HARMONY') COLLATE utf8mb4_unicode_ci NOT NULL,
  `token` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8se1i37nto56x9252rmrit8ib` (`token`),
  KEY `FKhc7d11bnr8x9gs5biohdhnx1c` (`user_id`),
  CONSTRAINT `FKhc7d11bnr8x9gs5biohdhnx1c` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `friendships` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `accepted_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `friend_alias` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_blocked` bit(1) DEFAULT NULL,
  `is_pinned` bit(1) DEFAULT NULL,
  `status` enum('PENDING','ACCEPTED','DECLINED','BLOCKED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `friend_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKjwaac0iw9d1fu58mx7afwf9f4` (`user_id`,`friend_id`),
  KEY `FKt0mh1j446gu5rqba17rnknuil` (`friend_id`),
  CONSTRAINT `FK4mcscxflf13uk72aupf6uwbgn` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKt0mh1j446gu5rqba17rnknuil` FOREIGN KEY (`friend_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `duration` int DEFAULT NULL,
  `encrypted_content` blob,
  `encryption_version` int DEFAULT NULL,
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `file_type` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `height` int DEFAULT NULL,
  `is_anonymous` bit(1) DEFAULT NULL,
  `is_deleted` bit(1) DEFAULT NULL,
  `is_edited` bit(1) DEFAULT NULL,
  `message_status` enum('SENDING','SENT','DELIVERED','READ','FAILED') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message_type` enum('TEXT','IMAGE','FILE','VOICE','VIDEO','AUDIO','LOCATION','SYSTEM') COLLATE utf8mb4_unicode_ci NOT NULL,
  `read_count` int DEFAULT NULL,
  `self_destruct_at` datetime(6) DEFAULT NULL,
  `self_destruct_seconds` int DEFAULT NULL,
  `thumbnail_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `width` int DEFAULT NULL,
  `anonymous_identity_id` bigint DEFAULT NULL,
  `chat_room_id` bigint NOT NULL,
  `reply_to_message_id` bigint DEFAULT NULL,
  `sender_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9qya9nttrdk67ibwpu1qbq3so` (`anonymous_identity_id`),
  KEY `FK67lyatc9udvn9fgepx08ckmbt` (`chat_room_id`),
  KEY `FKa0efscl1qaot4lml4w4gpydo2` (`reply_to_message_id`),
  KEY `FK4ui4nnwntodh6wjvck53dbk9m` (`sender_id`),
  CONSTRAINT `FK4ui4nnwntodh6wjvck53dbk9m` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FK67lyatc9udvn9fgepx08ckmbt` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `FK9qya9nttrdk67ibwpu1qbq3so` FOREIGN KEY (`anonymous_identity_id`) REFERENCES `anonymous_identities` (`id`),
  CONSTRAINT `FKa0efscl1qaot4lml4w4gpydo2` FOREIGN KEY (`reply_to_message_id`) REFERENCES `messages` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `user_key_bundles` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `identity_public_key` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `key_version` int DEFAULT NULL,
  `one_time_pre_keys` text COLLATE utf8mb4_unicode_ci,
  `signed_pre_key` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `signed_pre_key_signature` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_cp7h90997c1h81amtlfj6edgj` (`user_id`),
  CONSTRAINT `FKk0oe2xeii7nrvb5k8682hbyfx` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `user_roles` (
  `user_id` bigint NOT NULL,
  `role` enum('USER','ADMIN','MODERATOR') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  KEY `FKhfh9dx7w3ubf1co1vdev94g3f` (`user_id`),
  CONSTRAINT `FKhfh9dx7w3ubf1co1vdev94g3f` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `avatar_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `bio` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `display_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_active` bit(1) DEFAULT NULL,
  `last_seen` datetime(6) DEFAULT NULL,
  `online_status` enum('ONLINE','AWAY','BUSY','OFFLINE') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `UK_r43af9ap4edm43mmtq01oddj6` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS=1;
