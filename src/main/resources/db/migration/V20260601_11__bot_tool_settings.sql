ALTER TABLE bot_configs
    ADD COLUMN enabled_tools TEXT NULL,
    ADD COLUMN max_agent_iterations INT NOT NULL DEFAULT 8,
    ADD COLUMN max_agent_wallclock_ms INT NOT NULL DEFAULT 30000,
    ADD COLUMN max_agent_total_tokens INT NOT NULL DEFAULT 50000;
