ALTER TABLE bot_configs
    ADD COLUMN image_rewrite_failure_policy VARCHAR(32) NOT NULL
        DEFAULT 'USE_SOURCE_PROMPT'
        AFTER image_prompt_mode;
