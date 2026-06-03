ALTER TABLE chat_rooms
    ADD COLUMN announcement TEXT NULL,
    ADD COLUMN announcement_updated_at DATETIME(6) NULL,
    ADD COLUMN announcement_updated_by BIGINT NULL;

ALTER TABLE chat_rooms
    ADD CONSTRAINT FK_chat_rooms_announcement_updated_by
        FOREIGN KEY (announcement_updated_by) REFERENCES users (id);

CREATE INDEX IDX_chat_rooms_announcement_updated_at
    ON chat_rooms (announcement_updated_at);
