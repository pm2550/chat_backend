ALTER TABLE messages
    ADD COLUMN forwarded_from_message_id BIGINT NULL,
    ADD CONSTRAINT fk_messages_forwarded_from
        FOREIGN KEY (forwarded_from_message_id) REFERENCES messages(id);

CREATE TABLE chat_room_pinned_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    pinned_by_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_room_pin_message UNIQUE (chat_room_id, message_id),
    CONSTRAINT fk_room_pin_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_room_pin_message FOREIGN KEY (message_id) REFERENCES messages(id),
    CONSTRAINT fk_room_pin_user FOREIGN KEY (pinned_by_id) REFERENCES users(id)
);

CREATE TABLE message_stars (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_message_star_user UNIQUE (message_id, user_id),
    CONSTRAINT fk_message_star_message FOREIGN KEY (message_id) REFERENCES messages(id),
    CONSTRAINT fk_message_star_user FOREIGN KEY (user_id) REFERENCES users(id)
);
