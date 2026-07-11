ALTER TABLE `provider_credentials`
  MODIFY COLUMN `llm_provider`
    enum('OPENAI','CLAUDE','DEEPSEEK','OLLAMA','HERMES','DASHSCOPE','KIMI','IMAGE_API','NOVELAI')
    NOT NULL;

ALTER TABLE `bot_configs`
  ADD COLUMN `image_generation_provider` varchar(32) NOT NULL DEFAULT 'HERMES' AFTER `workflow_mode`,
  ADD COLUMN `image_provider_credential_id` bigint DEFAULT NULL AFTER `image_generation_provider`,
  ADD COLUMN `image_model` varchar(120) DEFAULT NULL AFTER `image_provider_credential_id`,
  ADD COLUMN `image_negative_prompt` text DEFAULT NULL AFTER `image_model`,
  ADD KEY `idx_bot_configs_image_provider_credential` (`image_provider_credential_id`),
  ADD CONSTRAINT `fk_bot_configs_image_provider_credential`
    FOREIGN KEY (`image_provider_credential_id`) REFERENCES `provider_credentials` (`id`)
    ON DELETE SET NULL;
