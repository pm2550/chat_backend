-- Let the built-in Agent actually equip profile/name cards such as 高大师/陆大师.
UPDATE bot_configs
SET enabled_tools = CASE
    WHEN enabled_tools IS NULL OR TRIM(enabled_tools) = '' THEN '["set_my_profile_card"]'
    WHEN JSON_VALID(enabled_tools) AND JSON_CONTAINS(enabled_tools, JSON_QUOTE('set_my_profile_card'), '$') = 0
        THEN JSON_ARRAY_APPEND(enabled_tools, '$', 'set_my_profile_card')
    ELSE enabled_tools
END,
updated_at = NOW(6)
WHERE bot_name = 'Agent'
  AND created_by IS NULL
  AND (enabled_tools IS NULL
       OR TRIM(enabled_tools) = ''
       OR (JSON_VALID(enabled_tools) AND JSON_CONTAINS(enabled_tools, JSON_QUOTE('set_my_profile_card'), '$') = 0));
