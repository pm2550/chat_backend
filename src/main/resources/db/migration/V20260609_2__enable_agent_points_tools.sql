-- Let the built-in Agent answer point-balance questions through the normal
-- tool loop. Keep the update scoped to the system Agent bot.
UPDATE bot_configs
SET enabled_tools = JSON_ARRAY(
    'read_recent_messages',
    'search_messages',
    'web_search',
    'get_room_members',
    'get_local_room_settings',
    'get_open_chat_panels',
    'get_recent_attachments',
    'prompt_user_confirmation',
    'read_clipboard',
    'lookup_my_points_balance',
    'lookup_points_features'
)
WHERE bot_name = 'Agent'
  AND created_by IS NULL;
