ALTER TABLE bot_configs
    ADD COLUMN max_history_messages INT NOT NULL DEFAULT 20,
    ADD COLUMN include_room_metadata BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN system_prompt_template TEXT NULL,
    ADD COLUMN max_context_tokens_estimate INT NOT NULL DEFAULT 6000;

-- Batch 8A seeded the built-in Agent identity before chat providers were wired
-- into the context envelope. Use Hermes for text chat; DashScope remains image-only.
UPDATE bot_configs
SET llm_provider = 'HERMES',
    model_name = 'hermes-agent'
WHERE bot_name = 'Agent'
  AND created_by IS NULL
  AND llm_provider = 'DASHSCOPE';
