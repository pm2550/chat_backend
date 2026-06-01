ALTER TABLE chat_room_members
  ADD COLUMN is_pinned bit(1) DEFAULT 0 AFTER is_muted;

CREATE TABLE user_settings (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL,
  message_notifications_enabled bit(1) DEFAULT 1,
  show_online_status bit(1) DEFAULT 1,
  allow_friend_requests bit(1) DEFAULT 1,
  allow_direct_messages bit(1) DEFAULT 1,
  read_receipts_enabled bit(1) DEFAULT 1,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY UK_user_settings_user_id (user_id),
  CONSTRAINT FK_user_settings_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_room_clear_states (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL,
  chat_room_id bigint NOT NULL,
  cleared_at datetime(6) NOT NULL,
  created_at datetime(6) DEFAULT NULL,
  updated_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY UK_clear_state_user_room (user_id, chat_room_id),
  KEY FK_clear_state_room (chat_room_id),
  CONSTRAINT FK_clear_state_user FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT FK_clear_state_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
