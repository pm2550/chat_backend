CREATE TABLE web_push_subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    endpoint TEXT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL,
    p256dh VARCHAR(512) NOT NULL,
    auth VARCHAR(255) NOT NULL,
    user_agent VARCHAR(512) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_error VARCHAR(512) NULL,
    last_error_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_web_push_endpoint_hash UNIQUE (endpoint_hash),
    CONSTRAINT fk_web_push_subscriptions_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_web_push_user_active (user_id, is_active)
);
