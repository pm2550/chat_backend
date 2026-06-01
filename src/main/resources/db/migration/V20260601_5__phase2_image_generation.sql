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
        'IMAGE_GENERATION',
        'SYSTEM'
    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE messages
    ADD COLUMN image_gen_prompt TEXT NULL AFTER poll_id,
    ADD COLUMN image_gen_status ENUM('QUEUED','PROCESSING','DONE','FAILED') NULL AFTER image_gen_prompt,
    ADD COLUMN image_gen_url VARCHAR(500) NULL AFTER image_gen_status,
    ADD COLUMN image_gen_provider_task_id VARCHAR(128) NULL AFTER image_gen_url;

ALTER TABLE bot_configs
    MODIFY COLUMN llm_provider ENUM('OPENAI','CLAUDE','DEEPSEEK','OLLAMA','HERMES','DASHSCOPE')
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE provider_credentials
    MODIFY COLUMN llm_provider ENUM('OPENAI','CLAUDE','DEEPSEEK','OLLAMA','HERMES','DASHSCOPE')
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;

INSERT INTO feature_costs (feature_key, cost_points, free_daily_quota, description)
VALUES ('image_generation', 10, 0, 'AI image generation in chat')
ON DUPLICATE KEY UPDATE
    cost_points = VALUES(cost_points),
    description = VALUES(description);
