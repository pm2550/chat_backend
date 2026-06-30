ALTER TABLE bot_configs
    ADD COLUMN reply_mode VARCHAR(32) NOT NULL DEFAULT 'SINGLE' AFTER max_tokens;
