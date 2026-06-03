CREATE TABLE IF NOT EXISTS message_read_receipts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_read_receipt UNIQUE (message_id, user_id),
    CONSTRAINT fk_read_receipt_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_read_receipt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
