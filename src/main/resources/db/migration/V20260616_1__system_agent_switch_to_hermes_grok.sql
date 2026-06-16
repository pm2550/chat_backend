-- Switch the built-in Agent from Ollama/Kimi back to Hermes /chat Grok.
-- Hermes /chat now supports tool_calls, so point lookup and other tools remain available.
UPDATE bot_configs
SET llm_provider = 'HERMES',
    model_name = 'grok-4.3'
WHERE bot_name = 'Agent'
  AND created_by IS NULL
  AND llm_provider = 'OLLAMA';
