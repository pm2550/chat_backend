-- F5 Slice 2: a per-room moderation grant for bots. A room OWNER can grant a bound bot the
-- power to mute or kick non-privileged members; the bot's moderation agent-tools enforce it.
-- Stored as varchar (entity uses @Enumerated(STRING) + @JdbcTypeCode(VARCHAR)) per the
-- content_format crash lesson — never map an @Enumerated field onto a non-enum column.
ALTER TABLE chat_room_bots
  ADD COLUMN moderation_grant varchar(16) NOT NULL DEFAULT 'NONE';
