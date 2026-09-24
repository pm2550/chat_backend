-- 端到端加密恢复码：每把身份私钥除了"登录密码包装"之外，再存一份"恢复码包装"。
-- 恢复码只在客户端生成和显示，服务器只存包装后的密文、盐和派生参数，拿不到恢复码本身。
-- 三列都可以为空：还没设置恢复码的账号、以及老客户端新建的密钥都没有这一份。
ALTER TABLE `e2ee_identity_keys`
  ADD COLUMN `recovery_wrapped_private_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `recovery_wrap_salt` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  ADD COLUMN `recovery_wrap_params` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL;
