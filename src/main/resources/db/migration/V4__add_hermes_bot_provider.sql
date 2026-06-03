ALTER TABLE `bot_configs`
  MODIFY COLUMN `llm_provider` enum('OPENAI','CLAUDE','DEEPSEEK','OLLAMA','HERMES')
  COLLATE utf8mb4_unicode_ci NOT NULL;
