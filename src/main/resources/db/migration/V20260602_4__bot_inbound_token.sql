-- Phase 4 (F1) Slice 1: give a bot a machine identity so an external service
-- (the owner's OpenClaw / a TG/QQ bridge) can post messages AS the bot via a token.
-- Only the fingerprint + last4 are stored (never the raw token), mirroring provider_credentials.
ALTER TABLE `bot_configs`
  ADD COLUMN `inbound_token_fingerprint` varchar(64) DEFAULT NULL,
  ADD COLUMN `inbound_token_last4` varchar(8) DEFAULT NULL,
  ADD UNIQUE KEY `uk_bot_configs_inbound_token` (`inbound_token_fingerprint`);
