-- 受保护文件的访问判定按 file_url / image_gen_url 找引用消息（转发、贴纸会让多条消息共用同一文件），
-- 消息过期清理也按同样的条件判断文件是否还被引用。两列都没有索引时每次看图都是全表扫描。
CREATE INDEX idx_messages_file_url ON messages (file_url);
CREATE INDEX idx_messages_image_gen_url ON messages (image_gen_url);
