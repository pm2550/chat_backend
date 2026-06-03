UPDATE bot_configs
SET enabled_tools = '["read_recent_messages","search_messages","web_search","get_room_members","get_local_room_settings","get_open_chat_panels","get_recent_attachments","prompt_user_confirmation","read_clipboard"]'
WHERE bot_name = 'Agent'
  AND created_by IS NULL
  AND (enabled_tools IS NULL OR enabled_tools = '');
