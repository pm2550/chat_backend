ALTER TABLE user_settings
  ADD COLUMN chat_background_preset varchar(50) NOT NULL DEFAULT 'cloud_gradient' AFTER read_receipts_enabled,
  ADD COLUMN chat_background_custom_url varchar(500) DEFAULT NULL AFTER chat_background_preset,
  ADD COLUMN avatar_frame_preset varchar(50) NOT NULL DEFAULT 'none' AFTER chat_background_custom_url,
  ADD COLUMN bubble_style_preset varchar(50) NOT NULL DEFAULT 'default_gradient' AFTER avatar_frame_preset;

ALTER TABLE chat_rooms
  ADD COLUMN custom_background_preset varchar(50) DEFAULT NULL AFTER avatar_url,
  ADD COLUMN custom_background_url varchar(500) DEFAULT NULL AFTER custom_background_preset;
