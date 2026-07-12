ALTER TABLE bot_configs
    ADD COLUMN vision_input_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN history_image_inspection_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Existing tool-enabled bots gain the room-scoped image selector. Tool-less persona
-- bots keep their one-shot execution mode until their owner saves the new toggle.
UPDATE bot_configs
SET enabled_tools = JSON_ARRAY_APPEND(CAST(enabled_tools AS JSON), '$', 'inspect_room_image')
WHERE enabled_tools IS NOT NULL
  AND JSON_VALID(enabled_tools)
  AND JSON_LENGTH(CAST(enabled_tools AS JSON)) > 0
  AND JSON_SEARCH(CAST(enabled_tools AS JSON), 'one', 'inspect_room_image') IS NULL;
