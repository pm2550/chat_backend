ALTER TABLE chat_room_members
    ADD COLUMN hidden_at datetime(6) DEFAULT NULL AFTER unread_count,
    ADD COLUMN is_blocked bit(1) NOT NULL DEFAULT b'0' AFTER hidden_at,
    ADD COLUMN cleared_before_message_id bigint DEFAULT NULL AFTER is_blocked;

CREATE INDEX idx_chat_room_members_user_display_state
    ON chat_room_members (user_id, is_blocked, hidden_at);

CREATE INDEX idx_chat_room_members_room_blocked
    ON chat_room_members (chat_room_id, is_blocked);
