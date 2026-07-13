ALTER TABLE bot_configs
    ADD COLUMN reply_interval_seconds DECIMAL(5,2) NOT NULL DEFAULT 2.00 AFTER reply_mode;
