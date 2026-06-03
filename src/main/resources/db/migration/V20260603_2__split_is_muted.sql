-- F5 / Item 5: split the overloaded `is_muted` column on chat_room_members.
--
-- `is_muted` was READ by MessageService.validateCanSendMessage (blocks sending) AND
-- WRITTEN by both updateNotificationMuted (a user self-muting their own notifications)
-- and the admin / bot-moderation path (toggleMuteStatus / setMemberMuted). Net effect:
-- a user who self-muted notifications was also blocked from SENDING messages. Real bug.
--
-- This migration adds two explicit columns. `is_bot_muted` carries the moderation
-- (send-blocking) concern; `is_notification_muted` carries the user's own push-mute.
-- `is_muted` is KEPT as a dual-write shadow during the transition and is dropped in a
-- follow-up V20260604_x once the dual-write code has been live for at least one release.
--
-- bit(1) (not tinyint) to mirror the sibling is_muted column exactly and the project's
-- dominant boolean convention; validated under ddl-auto: validate against `Boolean` fields.
ALTER TABLE chat_room_members
  ADD COLUMN is_bot_muted bit(1) NOT NULL DEFAULT b'0',
  ADD COLUMN is_notification_muted bit(1) NOT NULL DEFAULT b'0';

-- Conservative backfill: existing is_muted=1 rows are treated as moderation mutes
-- (F5 just landed, and production was using is_muted primarily for the admin / bot
-- moderation path). Users who self-muted notifications will re-toggle after the
-- notification settings UI ships; acceptable transition cost vs. unblocking the
-- send-path bug now. NULL is_muted rows are not matched and stay at the default 0.
UPDATE chat_room_members SET is_bot_muted = b'1' WHERE is_muted = b'1';

-- is_notification_muted intentionally left at its default 0; do NOT backfill from
-- is_muted (that would re-create the original conflation this migration removes).
