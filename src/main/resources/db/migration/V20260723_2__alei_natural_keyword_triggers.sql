-- Mirror Kirara's human-readable keyword rules without changing the user's
-- selected provider or model.
UPDATE bot_configs
SET default_trigger_mode = 'MENTION_OR_KEYWORD',
    default_trigger_keywords = '/chat,找一下,查一下,搜一下,看一下,找找,查查,搜搜,看看,回顾,翻翻,历史,记录,上次,之前,以前,之前说,上次说,你记得,记得吗,还记得,你知道,知道吗,如何评价,评价一下,怎么评价,如何看待,评价评价,有没有说过,说过吗,聊过,聊了,讨论过,提到过,是谁,是什么,在哪里,谁,哪个,什么,哪,如何,评价,刚刚,为何'
WHERE bot_name = '阿雷';

UPDATE chat_room_bots crb
JOIN bot_configs bc ON bc.id = crb.bot_config_id
SET crb.trigger_mode = 'MENTION_OR_KEYWORD',
    crb.trigger_keywords = bc.default_trigger_keywords
WHERE bc.bot_name = '阿雷';
