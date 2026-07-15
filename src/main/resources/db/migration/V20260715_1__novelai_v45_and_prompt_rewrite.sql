ALTER TABLE `bot_configs`
  ADD COLUMN `image_prompt_mode` varchar(32) NOT NULL DEFAULT 'FAITHFUL_CREATIVE'
  AFTER `image_negative_prompt`;

UPDATE `bot_configs`
SET `image_model` = 'nai-diffusion-4-5-full'
WHERE `image_generation_provider` = 'NOVELAI'
  AND (`image_model` IS NULL OR `image_model` = '' OR `image_model` = 'nai-diffusion-3');
