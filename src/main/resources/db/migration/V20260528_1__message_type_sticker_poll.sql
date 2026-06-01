ALTER TABLE messages
    MODIFY COLUMN message_type ENUM(
        'TEXT',
        'IMAGE',
        'FILE',
        'VOICE',
        'VIDEO',
        'AUDIO',
        'LOCATION',
        'STICKER',
        'POLL',
        'SYSTEM'
    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
