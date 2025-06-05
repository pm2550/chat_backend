-- 聊天应用数据库创建脚本
-- MySQL版本

-- 创建数据库
CREATE DATABASE IF NOT EXISTS chatapp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE chatapp;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码(加密)',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    display_name VARCHAR(100) COMMENT '显示名称',
    avatar_url VARCHAR(500) COMMENT '头像URL',
    bio TEXT COMMENT '个人简介',
    online_status ENUM('ONLINE', 'AWAY', 'BUSY', 'OFFLINE') DEFAULT 'OFFLINE' COMMENT '在线状态',
    last_seen DATETIME COMMENT '最后在线时间',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否激活',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_online_status (online_status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 2. 用户角色表
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role ENUM('USER', 'ADMIN', 'MODERATOR') NOT NULL COMMENT '用户角色',
    
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色表';

-- 3. 聊天室表
CREATE TABLE IF NOT EXISTS chat_rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '聊天室名称',
    description TEXT COMMENT '聊天室描述',
    room_type ENUM('PRIVATE', 'GROUP', 'CHANNEL', 'PUBLIC') NOT NULL COMMENT '聊天室类型',
    avatar_url VARCHAR(500) COMMENT '聊天室头像URL',
    created_by BIGINT COMMENT '创建者ID',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否激活',
    is_private BOOLEAN DEFAULT FALSE COMMENT '是否私有',
    max_members INT DEFAULT 500 COMMENT '最大成员数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_room_type (room_type),
    INDEX idx_created_by (created_by),
    INDEX idx_is_active (is_active),
    INDEX idx_is_private (is_private),
    INDEX idx_created_at (created_at),
    INDEX idx_updated_at (updated_at),
    
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天室表';

-- 4. 聊天室成员表
CREATE TABLE IF NOT EXISTS chat_room_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    chat_room_id BIGINT NOT NULL COMMENT '聊天室ID',
    member_role ENUM('OWNER', 'ADMIN', 'MODERATOR', 'MEMBER') DEFAULT 'MEMBER' COMMENT '成员角色',
    nickname VARCHAR(100) COMMENT '群内昵称',
    is_muted BOOLEAN DEFAULT FALSE COMMENT '是否被禁言',
    is_admin BOOLEAN DEFAULT FALSE COMMENT '是否为管理员',
    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    last_read_message_id BIGINT COMMENT '最后阅读消息ID',
    unread_count INT DEFAULT 0 COMMENT '未读消息数量',
    
    UNIQUE KEY uk_user_chatroom (user_id, chat_room_id),
    INDEX idx_user_id (user_id),
    INDEX idx_chat_room_id (chat_room_id),
    INDEX idx_member_role (member_role),
    INDEX idx_joined_at (joined_at),
    
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天室成员表';

-- 5. 消息表
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    content TEXT COMMENT '消息内容',
    message_type ENUM('TEXT', 'IMAGE', 'FILE', 'VOICE', 'VIDEO', 'LOCATION', 'SYSTEM') DEFAULT 'TEXT' COMMENT '消息类型',
    sender_id BIGINT NOT NULL COMMENT '发送者ID',
    chat_room_id BIGINT NOT NULL COMMENT '聊天室ID',
    reply_to_message_id BIGINT COMMENT '回复消息ID',
    file_url VARCHAR(500) COMMENT '文件URL',
    file_name VARCHAR(255) COMMENT '文件名',
    file_size BIGINT COMMENT '文件大小(字节)',
    file_type VARCHAR(100) COMMENT '文件类型',
    thumbnail_url VARCHAR(500) COMMENT '缩略图URL',
    duration INT COMMENT '音频/视频时长(秒)',
    width INT COMMENT '图片/视频宽度',
    height INT COMMENT '图片/视频高度',
    message_status ENUM('SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED') DEFAULT 'SENT' COMMENT '消息状态',
    is_deleted BOOLEAN DEFAULT FALSE COMMENT '是否已删除',
    is_edited BOOLEAN DEFAULT FALSE COMMENT '是否已编辑',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    read_count INT DEFAULT 0 COMMENT '阅读数量',
    
    INDEX idx_sender_id (sender_id),
    INDEX idx_chat_room_id (chat_room_id),
    INDEX idx_message_type (message_type),
    INDEX idx_message_status (message_status),
    INDEX idx_created_at (created_at),
    INDEX idx_is_deleted (is_deleted),
    INDEX idx_reply_to_message_id (reply_to_message_id),
    
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (reply_to_message_id) REFERENCES messages(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- 创建初始数据
-- 插入管理员用户 (密码: admin123，需要在应用中加密)
INSERT INTO users (username, password, email, display_name, online_status, is_active) VALUES 
('admin', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', 'admin@chatapp.com', '系统管理员', 'OFFLINE', TRUE);

-- 为管理员分配角色
INSERT INTO user_roles (user_id, role) VALUES 
(1, 'ADMIN'),
(1, 'USER');

-- 创建系统公告频道
INSERT INTO chat_rooms (name, description, room_type, created_by, is_private) VALUES 
('系统公告', '系统公告和通知', 'CHANNEL', 1, FALSE);

-- 将管理员加入系统公告频道
INSERT INTO chat_room_members (user_id, chat_room_id, member_role) VALUES 
(1, 1, 'OWNER');

-- 插入欢迎消息
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status) VALUES 
('欢迎使用聊天应用！', 'SYSTEM', 1, 1, 'SENT');
