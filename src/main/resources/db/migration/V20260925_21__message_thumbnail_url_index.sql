-- 图片消息的小预览图（thumbnail_url）和原图同样受保护：访问判定、消息过期清理都会按 thumbnail_url 找引用消息，
-- 没有索引时每加载一张缩略图都是全表扫描（同 V20260924_31 的 file_url / image_gen_url）。
CREATE INDEX idx_messages_thumbnail_url ON messages (thumbnail_url);
