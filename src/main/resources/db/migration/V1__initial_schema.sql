-- Initial schema migration
-- This baseline migration captures the existing schema

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    phone VARCHAR(20),
    display_name VARCHAR(100),
    avatar_url VARCHAR(500),
    bio TEXT,
    online_status ENUM('ONLINE', 'AWAY', 'BUSY', 'OFFLINE') DEFAULT 'OFFLINE',
    last_seen DATETIME,
    is_active BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_online_status (online_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role ENUM('USER', 'ADMIN', 'MODERATOR') NOT NULL,
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendships (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    friend_id BIGINT NOT NULL,
    status ENUM('PENDING', 'ACCEPTED', 'DECLINED', 'BLOCKED') DEFAULT 'PENDING',
    friend_alias VARCHAR(100),
    is_blocked BOOLEAN DEFAULT FALSE,
    is_pinned BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    accepted_at DATETIME,
    UNIQUE KEY uk_friendship (user_id, friend_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (friend_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_status (user_id, status),
    INDEX idx_friend_status (friend_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    room_type ENUM('PRIVATE', 'GROUP', 'CHANNEL', 'PUBLIC') NOT NULL,
    avatar_url VARCHAR(500),
    created_by BIGINT,
    is_active BOOLEAN DEFAULT TRUE,
    is_private BOOLEAN DEFAULT FALSE,
    max_members INT DEFAULT 500,
    anonymous_enabled BOOLEAN DEFAULT FALSE,
    anonymous_theme VARCHAR(50) DEFAULT 'default',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_room_type (room_type),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_room_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    chat_room_id BIGINT NOT NULL,
    member_role ENUM('MEMBER', 'ADMIN', 'OWNER') DEFAULT 'MEMBER',
    nickname VARCHAR(100),
    is_muted BOOLEAN DEFAULT FALSE,
    is_admin BOOLEAN DEFAULT FALSE,
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_read_message_id BIGINT,
    unread_count INT DEFAULT 0,
    UNIQUE KEY uk_room_member (user_id, chat_room_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    INDEX idx_chat_room (chat_room_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    content TEXT,
    message_type ENUM('TEXT', 'IMAGE', 'FILE', 'VOICE', 'VIDEO', 'LOCATION', 'SYSTEM') NOT NULL DEFAULT 'TEXT',
    sender_id BIGINT NOT NULL,
    chat_room_id BIGINT NOT NULL,
    reply_to_message_id BIGINT,
    file_url VARCHAR(500),
    file_name VARCHAR(255),
    file_size BIGINT,
    file_type VARCHAR(100),
    thumbnail_url VARCHAR(500),
    duration INT,
    width INT,
    height INT,
    message_status ENUM('SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED') DEFAULT 'SENT',
    is_deleted BOOLEAN DEFAULT FALSE,
    is_edited BOOLEAN DEFAULT FALSE,
    is_anonymous BOOLEAN DEFAULT FALSE,
    anonymous_identity_id BIGINT,
    self_destruct_seconds INT,
    self_destruct_at DATETIME,
    encrypted_content BLOB,
    encryption_version INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    read_count INT DEFAULT 0,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (reply_to_message_id) REFERENCES messages(id) ON DELETE SET NULL,
    INDEX idx_chat_room_created (chat_room_id, created_at),
    INDEX idx_sender (sender_id),
    INDEX idx_self_destruct (self_destruct_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_read_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_user (message_id, user_id),
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_read (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    sender_id BIGINT,
    receiver_id BIGINT,
    content TEXT,
    message_type ENUM('TEXT', 'IMAGE', 'FILE', 'VOICE', 'VIDEO', 'LOCATION', 'SYSTEM') DEFAULT 'TEXT',
    file_url VARCHAR(500),
    file_name VARCHAR(255),
    reply_to_id BIGINT,
    is_recalled BOOLEAN DEFAULT FALSE,
    is_deleted BOOLEAN DEFAULT FALSE,
    sent_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    sender_name VARCHAR(100),
    sender_avatar VARCHAR(500),
    INDEX idx_chat_room_sent (chat_room_id, sent_at),
    INDEX idx_sender (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Anonymous identities table
CREATE TABLE IF NOT EXISTS anonymous_identities (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    chat_room_id BIGINT NOT NULL,
    anonymous_name VARCHAR(50) NOT NULL,
    anonymous_avatar VARCHAR(500),
    assigned_date DATE NOT NULL,
    custom_name_used BOOLEAN DEFAULT FALSE,
    UNIQUE KEY uk_user_room_date (user_id, chat_room_id, assigned_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    INDEX idx_room_date (chat_room_id, assigned_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User encryption keys table
CREATE TABLE IF NOT EXISTS user_key_bundles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    identity_public_key TEXT NOT NULL,
    signed_pre_key TEXT NOT NULL,
    signed_pre_key_signature TEXT NOT NULL,
    one_time_pre_keys TEXT,
    key_version INT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bot configurations table
CREATE TABLE IF NOT EXISTS bot_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_name VARCHAR(100) NOT NULL,
    bot_avatar VARCHAR(500),
    llm_provider ENUM('OPENAI', 'CLAUDE', 'DEEPSEEK', 'OLLAMA') NOT NULL,
    api_key_encrypted VARCHAR(500),
    model_name VARCHAR(100),
    system_prompt TEXT,
    temperature DOUBLE DEFAULT 0.7,
    max_tokens INT DEFAULT 2048,
    is_active BOOLEAN DEFAULT TRUE,
    created_by BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Bot-ChatRoom association table
CREATE TABLE IF NOT EXISTS chat_room_bots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    bot_config_id BIGINT NOT NULL,
    trigger_mode ENUM('MENTION', 'KEYWORD', 'ALL') DEFAULT 'MENTION',
    trigger_keywords VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    added_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_room_bot (chat_room_id, bot_config_id),
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (bot_config_id) REFERENCES bot_configs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Device tokens for push notifications
CREATE TABLE IF NOT EXISTS device_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(500) NOT NULL,
    platform ENUM('ANDROID', 'IOS', 'WEB', 'WINDOWS', 'MACOS', 'HARMONY') NOT NULL,
    device_info VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_token (token),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_platform (user_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
