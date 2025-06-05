-- 聊天应用测试数据插入脚本
-- 注意：在生产环境中请删除此文件或修改密码

USE chatapp;

-- 插入测试用户 (密码都是: 123456)
INSERT INTO users (username, password, email, phone, display_name, bio, online_status, is_active) VALUES 
('zhangsan', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', 'zhangsan@example.com', '13800138001', '张三', '这是张三的个人简介', 'ONLINE', TRUE),
('lisi', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', 'lisi@example.com', '13800138002', '李四', '这是李四的个人简介', 'OFFLINE', TRUE),
('wangwu', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', 'wangwu@example.com', '13800138003', '王五', '这是王五的个人简介', 'AWAY', TRUE),
('zhaoliu', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.', 'zhaoliu@example.com', '13800138004', '赵六', '这是赵六的个人简介', 'BUSY', TRUE);

-- 为测试用户分配普通用户角色
INSERT INTO user_roles (user_id, role) VALUES 
(2, 'USER'),
(3, 'USER'),
(4, 'USER'),
(5, 'USER');

-- 创建测试聊天室
INSERT INTO chat_rooms (name, description, room_type, created_by, is_private, max_members) VALUES 
('技术讨论群', '讨论技术相关话题', 'GROUP', 2, FALSE, 100),
('产品交流群', '产品经理和开发者交流', 'GROUP', 3, FALSE, 50),
('公开聊天室', '所有人都可以加入的聊天室', 'PUBLIC', 1, FALSE, 1000);

-- 将用户加入聊天室
-- 技术讨论群成员
INSERT INTO chat_room_members (user_id, chat_room_id, member_role, joined_at) VALUES 
(2, 2, 'OWNER', NOW()),
(3, 2, 'ADMIN', NOW()),
(4, 2, 'MEMBER', NOW()),
(5, 2, 'MEMBER', NOW());

-- 产品交流群成员
INSERT INTO chat_room_members (user_id, chat_room_id, member_role, joined_at) VALUES 
(3, 3, 'OWNER', NOW()),
(2, 3, 'MEMBER', NOW()),
(4, 3, 'MEMBER', NOW());

-- 公开聊天室成员
INSERT INTO chat_room_members (user_id, chat_room_id, member_role, joined_at) VALUES 
(1, 4, 'OWNER', NOW()),
(2, 4, 'ADMIN', NOW()),
(3, 4, 'MEMBER', NOW()),
(4, 4, 'MEMBER', NOW()),
(5, 4, 'MEMBER', NOW());

-- 创建一些私聊室
INSERT INTO chat_rooms (name, room_type, created_by, is_private) VALUES 
('私聊', 'PRIVATE', 2, TRUE),
('私聊', 'PRIVATE', 3, TRUE);

-- 私聊室成员 (张三和李四)
INSERT INTO chat_room_members (user_id, chat_room_id, member_role, joined_at) VALUES 
(2, 5, 'MEMBER', NOW()),
(3, 5, 'MEMBER', NOW());

-- 私聊室成员 (王五和赵六)
INSERT INTO chat_room_members (user_id, chat_room_id, member_role, joined_at) VALUES 
(4, 6, 'MEMBER', NOW()),
(5, 6, 'MEMBER', NOW());

-- 插入测试消息
-- 系统公告频道消息
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status, created_at) VALUES 
('系统维护通知：今晚22:00-23:00进行系统维护', 'SYSTEM', 1, 1, 'SENT', DATE_SUB(NOW(), INTERVAL 2 DAY)),
('新功能上线：支持文件传输和语音消息', 'SYSTEM', 1, 1, 'SENT', DATE_SUB(NOW(), INTERVAL 1 DAY));

-- 技术讨论群消息
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status, created_at) VALUES 
('大家好，欢迎加入技术讨论群！', 'TEXT', 2, 2, 'READ', DATE_SUB(NOW(), INTERVAL 5 HOUR)),
('最近在学习Spring Boot，有没有好的资源推荐？', 'TEXT', 4, 2, 'READ', DATE_SUB(NOW(), INTERVAL 4 HOUR)),
('可以看看官方文档，还有一些开源项目', 'TEXT', 3, 2, 'READ', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
('谢谢，我去看看', 'TEXT', 4, 2, 'READ', DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- 产品交流群消息
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status, created_at) VALUES 
('这个功能的用户体验需要优化一下', 'TEXT', 3, 3, 'READ', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
('具体是哪方面的问题？', 'TEXT', 2, 3, 'READ', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
('主要是响应速度有点慢', 'TEXT', 3, 3, 'DELIVERED', DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- 私聊消息 (张三和李四)
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status, created_at) VALUES 
('你好，今天有空吗？', 'TEXT', 2, 5, 'READ', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
('有空的，什么事？', 'TEXT', 3, 5, 'READ', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
('想约你一起吃饭，你觉得怎么样？', 'TEXT', 2, 5, 'DELIVERED', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
('好啊！什么时候？', 'TEXT', 3, 5, 'SENT', DATE_SUB(NOW(), INTERVAL 20 MINUTE)),
('晚上7点怎么样？我们在市中心的那家餐厅见面', 'TEXT', 2, 5, 'SENT', DATE_SUB(NOW(), INTERVAL 10 MINUTE));

-- 公开聊天室消息
INSERT INTO messages (content, message_type, sender_id, chat_room_id, message_status, created_at) VALUES 
('欢迎大家来到公开聊天室！', 'TEXT', 1, 4, 'READ', DATE_SUB(NOW(), INTERVAL 6 HOUR)),
('这里可以自由交流各种话题', 'TEXT', 1, 4, 'READ', DATE_SUB(NOW(), INTERVAL 5 HOUR)),
('大家好！', 'TEXT', 2, 4, 'READ', DATE_SUB(NOW(), INTERVAL 4 HOUR)),
('你好！', 'TEXT', 3, 4, 'READ', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
('最近天气不错呢', 'TEXT', 4, 4, 'DELIVERED', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
('是啊，很适合出去走走', 'TEXT', 5, 4, 'SENT', DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- 更新聊天室的最后活跃时间
UPDATE chat_rooms SET updated_at = (
    SELECT MAX(created_at) 
    FROM messages 
    WHERE messages.chat_room_id = chat_rooms.id
) WHERE id IN (1, 2, 3, 4, 5);

-- 更新用户的最后在线时间
UPDATE users SET last_seen = NOW() WHERE online_status IN ('ONLINE', 'AWAY', 'BUSY');
UPDATE users SET last_seen = DATE_SUB(NOW(), INTERVAL 30 MINUTE) WHERE online_status = 'OFFLINE';
