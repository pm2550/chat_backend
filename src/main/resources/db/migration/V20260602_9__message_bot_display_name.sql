ALTER TABLE messages
    ADD COLUMN bot_display_name VARCHAR(255) NULL AFTER bot_config_id;

UPDATE messages m
JOIN chat_room_bots crb
  ON crb.chat_room_id = m.chat_room_id
 AND crb.bot_config_id = m.bot_config_id
SET m.bot_display_name = COALESCE(NULLIF(TRIM(crb.room_nickname), ''), (
    SELECT bc.bot_name FROM bot_configs bc WHERE bc.id = m.bot_config_id
))
WHERE m.bot_config_id IS NOT NULL
  AND m.bot_display_name IS NULL;
