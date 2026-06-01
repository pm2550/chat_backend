CREATE TABLE anonymous_themes (
  id bigint NOT NULL AUTO_INCREMENT,
  theme_key varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  display_name varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  description varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  accent_color varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  background_color varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  message_color varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  persona_prefix varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  is_enabled bit(1) DEFAULT 1,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY UK_anonymous_themes_theme_key (theme_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO anonymous_themes
  (theme_key, display_name, description, accent_color, background_color, message_color, persona_prefix, is_enabled, created_at, updated_at)
VALUES
  ('default', '经典匿名', '轻量匿名身份，适合日常群聊和临时讨论。', '#7C3AED', '#F3E8FF', '#FFFFFF', '匿名', 1, NOW(6), NOW(6)),
  ('night_ops', '夜航频道', '深色、冷静、偏行动协作的匿名主题。', '#2563EB', '#DBEAFE', '#EFF6FF', '夜航', 1, NOW(6), NOW(6)),
  ('green_room', '绿室圆桌', '柔和青绿色匿名主题，适合头脑风暴和团队复盘。', '#0FAE96', '#E6F8F4', '#F7FFFD', '绿室', 1, NOW(6), NOW(6));

ALTER TABLE chat_rooms
  ADD COLUMN anonymous_theme_id bigint DEFAULT NULL AFTER anonymous_theme,
  ADD KEY FK_chat_rooms_anonymous_theme (anonymous_theme_id),
  ADD CONSTRAINT FK_chat_rooms_anonymous_theme
    FOREIGN KEY (anonymous_theme_id) REFERENCES anonymous_themes (id);

UPDATE chat_rooms cr
JOIN anonymous_themes t
  ON t.theme_key = COALESCE(NULLIF(cr.anonymous_theme, ''), 'default')
SET cr.anonymous_theme_id = t.id;

UPDATE chat_rooms cr
JOIN anonymous_themes t ON t.theme_key = 'default'
SET cr.anonymous_theme_id = t.id
WHERE cr.anonymous_theme_id IS NULL;

ALTER TABLE chat_room_bots
  ADD COLUMN room_nickname varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL AFTER trigger_keywords,
  ADD COLUMN room_prompt_suffix text COLLATE utf8mb4_unicode_ci AFTER room_nickname,
  ADD COLUMN enabled_in_room bit(1) DEFAULT 1 AFTER room_prompt_suffix;
