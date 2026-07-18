ALTER TABLE `bot_configs`
    ADD COLUMN `image_invocation_mode` VARCHAR(32) NOT NULL DEFAULT 'AGENT'
    AFTER `image_prompt_mode`;
