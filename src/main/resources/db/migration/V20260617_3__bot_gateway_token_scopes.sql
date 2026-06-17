ALTER TABLE bot_configs
    ADD COLUMN inbound_token_scopes TEXT NULL
        COMMENT 'Comma-separated long-lived bot gateway scopes such as message:send,message:read,room:manage,workspace:read,workspace:write,friend:send';

