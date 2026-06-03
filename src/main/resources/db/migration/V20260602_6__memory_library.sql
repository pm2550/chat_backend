-- Phase 5a (F2): per-room bot/user memory library. Bots persist durable facts via the
-- save_memory tool and retrieve them via recall_memory; users view / edit / pin / archive
-- the same entries through the memory API. v1 is keyword/substring match (no vector/RAG).
--
-- ENUM LESSON (content_format crash): source_type/visibility are @Enumerated(STRING) on the
-- entity but are stored as varchar here, with @JdbcTypeCode(VARCHAR) on the entity so
-- Hibernate ddl-auto:validate sees varchar<->varchar (a native enum column would crash
-- validate, and H2 — which builds from the entity in tests — cannot catch that mismatch).
CREATE TABLE IF NOT EXISTS `memory_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chat_room_id` bigint NOT NULL,
  `author_user_id` bigint DEFAULT NULL,
  `author_bot_config_id` bigint DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `content` text NOT NULL,
  `keywords` varchar(500) DEFAULT NULL,
  `source_type` varchar(16) NOT NULL DEFAULT 'USER',
  `visibility` varchar(16) NOT NULL DEFAULT 'ROOM',
  `is_pinned` bit(1) NOT NULL DEFAULT b'0',
  `is_archived` bit(1) NOT NULL DEFAULT b'0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_memory_room_active` (`chat_room_id`, `is_archived`),
  KEY `idx_memory_room_pinned` (`chat_room_id`, `is_pinned`),
  CONSTRAINT `fk_memory_room` FOREIGN KEY (`chat_room_id`)
    REFERENCES `chat_rooms` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_memory_bot` FOREIGN KEY (`author_bot_config_id`)
    REFERENCES `bot_configs` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
