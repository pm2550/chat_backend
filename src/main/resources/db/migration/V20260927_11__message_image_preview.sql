-- 图片消息的中图（长边约 1280px，只给大原图做）和服务器预览图的版本号。
-- preview_url 和 thumbnail_url 一样受保护：访问判定、消息过期清理都按它找引用消息，所以要索引。
-- rendition_version：NULL = 1.1.51 的 400px 缩略图（或客户端加密的），启动回填按它把老缩略图换成 720px。
ALTER TABLE messages ADD COLUMN preview_url VARCHAR(255) NULL;
ALTER TABLE messages ADD COLUMN rendition_version INT NULL;
CREATE INDEX idx_messages_preview_url ON messages (preview_url);
