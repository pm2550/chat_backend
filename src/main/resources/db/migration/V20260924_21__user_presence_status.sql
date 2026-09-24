-- 用户手动选择的在线状态（在线/离开/忙碌/隐身），与连接带来的在线/离线分开保存。
-- NULL 表示从未选择，登录时按"在线"处理。
ALTER TABLE `users`
  ADD COLUMN `presence_status` enum('ONLINE','AWAY','BUSY','OFFLINE')
  COLLATE utf8mb4_unicode_ci DEFAULT NULL AFTER `online_status`;

-- 旧逻辑每次登录都重置为 ONLINE，所以现在还是离开/忙碌的，一定是用户登录后自己选的。
UPDATE `users` SET `presence_status` = `online_status`
  WHERE `online_status` IN ('AWAY','BUSY');
