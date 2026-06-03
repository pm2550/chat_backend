-- F5 Slice 1: the room creator is the real OWNER (a role above ADMIN). Historically the
-- creator was added to chat_room_members as ADMIN; backfill them to OWNER so the new
-- owner-only operations (transfer ownership / manage roles) have a true owner to anchor on.
-- member_role is already a native enum('OWNER','ADMIN','MODERATOR','MEMBER'), so no schema
-- change is needed — only a data backfill.
-- Only GROUP rooms: PRIVATE (1-1) chats keep both members as equal peers (no owner).
UPDATE chat_room_members crm
JOIN chat_rooms cr ON cr.id = crm.chat_room_id
SET crm.member_role = 'OWNER', crm.is_admin = b'1'
WHERE crm.user_id = cr.created_by
  AND crm.member_role <> 'OWNER'
  AND cr.room_type <> 'PRIVATE';
