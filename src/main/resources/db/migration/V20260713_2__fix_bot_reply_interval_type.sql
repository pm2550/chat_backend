ALTER TABLE bot_configs
    MODIFY COLUMN reply_interval_seconds DOUBLE NOT NULL DEFAULT 2.0;
