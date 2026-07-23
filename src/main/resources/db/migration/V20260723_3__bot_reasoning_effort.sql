ALTER TABLE bot_configs
    ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'AUTO' AFTER max_tokens;

UPDATE bot_configs
SET reasoning_effort = 'NONE'
WHERE bot_name = '阿雷'
  AND llm_provider = 'OLLAMA'
  AND LOWER(COALESCE(model_name, '')) LIKE 'kimi-k2%';
