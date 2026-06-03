ALTER TABLE bot_configs
    ADD COLUMN character_card_json JSON NULL,
    ADD COLUMN character_persona TEXT NULL,
    ADD COLUMN character_scenario TEXT NULL,
    ADD COLUMN character_first_mes TEXT NULL,
    ADD COLUMN character_mes_example TEXT NULL,
    ADD COLUMN character_creator_notes TEXT NULL,
    ADD COLUMN character_system_prompt TEXT NULL,
    ADD COLUMN character_post_history_instructions TEXT NULL,
    ADD COLUMN character_alternate_greetings JSON NULL,
    ADD COLUMN character_book_json JSON NULL;
