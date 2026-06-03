-- Per-bot opt-in for passive [MEMORY] section injection into the agent system prompt.
-- Default TRUE: every existing bot starts receiving memory pre-warming automatically,
-- because MemoryService already privacy-gates visibility (ROOM scope only when userId=null).
ALTER TABLE bot_configs
    ADD COLUMN include_memory_section BOOLEAN NOT NULL DEFAULT TRUE;
