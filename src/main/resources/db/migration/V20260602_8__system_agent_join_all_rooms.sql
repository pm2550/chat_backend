-- Make the system Agent available through the normal @mention path in every
-- existing active room. New rooms are handled by ChatRoomService.
INSERT INTO chat_room_bots (
    chat_room_id,
    bot_config_id,
    trigger_mode,
    room_nickname,
    enabled_in_room,
    is_active,
    added_at
)
SELECT
    cr.id,
    bc.id,
    'MENTION',
    bc.bot_name,
    b'1',
    b'1',
    NOW(6)
FROM chat_rooms cr
JOIN bot_configs bc
  ON bc.bot_name = 'Agent'
 AND bc.created_by IS NULL
WHERE cr.is_active = b'1'
  AND NOT EXISTS (
      SELECT 1
      FROM chat_room_bots crb
      WHERE crb.chat_room_id = cr.id
        AND crb.bot_config_id = bc.id
  );
