INSERT INTO bot_configs (
    bot_name,
    bot_avatar,
    llm_provider,
    model_name,
    system_prompt,
    temperature,
    max_tokens,
    is_active,
    created_by,
    created_at,
    updated_at
)
SELECT
    'Agent',
    '/assets/agent-avatar.png',
    'DASHSCOPE',
    'system-agent',
    'You are a helpful agent for PM chat. Respond concisely.',
    0.7,
    2048,
    1,
    NULL,
    NOW(6),
    NOW(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_configs
    WHERE bot_name = 'Agent'
      AND created_by IS NULL
);
