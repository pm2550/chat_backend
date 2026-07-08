ALTER TABLE chat_room_bots
    MODIFY COLUMN trigger_mode ENUM('MENTION','KEYWORD','REGEX','ALL') NULL;
