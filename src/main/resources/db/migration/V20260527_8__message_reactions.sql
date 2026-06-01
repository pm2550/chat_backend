CREATE TABLE IF NOT EXISTS message_reactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    emoji VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_reaction UNIQUE (message_id, user_id, emoji),
    CONSTRAINT fk_message_reaction_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_message_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
