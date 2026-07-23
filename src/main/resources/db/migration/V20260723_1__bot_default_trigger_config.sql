ALTER TABLE chat_room_bots
    MODIFY COLUMN trigger_mode ENUM(
        'MENTION',
        'KEYWORD',
        'REGEX',
        'ALL',
        'MENTION_OR_KEYWORD',
        'MENTION_OR_REGEX'
    ) NULL;

ALTER TABLE bot_configs
    ADD COLUMN default_trigger_mode VARCHAR(32) NOT NULL DEFAULT 'MENTION' AFTER reply_interval_seconds,
    ADD COLUMN default_trigger_keywords VARCHAR(500) NULL AFTER default_trigger_mode;

-- QQbot/Kirara chat:normal triggers on either a bot mention or the /chat prefix.
UPDATE bot_configs
SET default_trigger_mode = 'MENTION_OR_REGEX',
    default_trigger_keywords = '^/chat(?:\\s|$)'
WHERE bot_name = '阿雷';

UPDATE chat_room_bots crb
JOIN bot_configs bc ON bc.id = crb.bot_config_id
SET crb.trigger_mode = 'MENTION_OR_REGEX',
    crb.trigger_keywords = '^/chat(?:\\s|$)'
WHERE bc.bot_name = '阿雷'
  AND crb.trigger_mode = 'MENTION'
  AND (crb.trigger_keywords IS NULL OR crb.trigger_keywords = '');
