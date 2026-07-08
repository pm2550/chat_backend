ALTER TABLE bot_configs
    ADD COLUMN workflow_mode VARCHAR(32) NOT NULL DEFAULT 'SINGLE_PASS' AFTER reply_mode;

UPDATE bot_configs
SET workflow_mode = 'KIRARA_TWO_PASS',
    reply_mode = 'CHUNKED',
    max_tokens = GREATEST(COALESCE(max_tokens, 0), 1200),
    max_history_messages = GREATEST(COALESCE(max_history_messages, 0), 40),
    include_room_metadata = TRUE,
    include_memory_section = TRUE
WHERE bot_name = '阿雷'
  AND created_by IS NOT NULL;
