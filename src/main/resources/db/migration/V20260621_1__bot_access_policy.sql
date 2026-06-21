ALTER TABLE bot_configs
    ADD COLUMN access_policy VARCHAR(32) NOT NULL DEFAULT 'PRIVATE';

UPDATE bot_configs
SET access_policy = 'PUBLIC'
WHERE created_by IS NULL;

CREATE TABLE bot_allowed_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_config_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bot_allowed_users_bot_user UNIQUE (bot_config_id, user_id),
    CONSTRAINT fk_bot_allowed_users_bot FOREIGN KEY (bot_config_id) REFERENCES bot_configs(id) ON DELETE CASCADE,
    CONSTRAINT fk_bot_allowed_users_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_bot_allowed_users_bot (bot_config_id),
    INDEX idx_bot_allowed_users_user (user_id)
);
