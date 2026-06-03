-- Rich content channel for bot/agent messages. NULL = legacy PLAIN behavior.
ALTER TABLE `messages`
  ADD COLUMN `content_format` varchar(16) DEFAULT NULL AFTER `message_type`;
