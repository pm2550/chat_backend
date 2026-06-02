-- Batch: switch system Agent bot from HERMES (no tool_calls) to Ollama Cloud Pro kimi-k2.6.
-- Lineage: V20260601_10 previously moved this row DASHSCOPE -> HERMES;
-- this migration moves HERMES -> OLLAMA. Idempotent: only fires if the row
-- is still on HERMES/hermes-agent. Re-running on an already-switched row is a no-op.
UPDATE bot_configs
SET llm_provider = 'OLLAMA',
    model_name   = 'kimi-k2.6'
WHERE bot_name = 'Agent'
  AND created_by IS NULL
  AND llm_provider = 'HERMES'
  AND model_name = 'hermes-agent';
