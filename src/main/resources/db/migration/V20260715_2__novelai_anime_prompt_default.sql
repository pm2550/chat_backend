ALTER TABLE `bot_configs`
  MODIFY COLUMN `image_prompt_mode` varchar(32) NOT NULL DEFAULT 'ANIME_CREATIVE';

UPDATE `bot_configs`
SET `image_prompt_mode` = 'ANIME_CREATIVE'
WHERE `image_generation_provider` = 'NOVELAI'
  AND `image_prompt_mode` = 'FAITHFUL_CREATIVE';
