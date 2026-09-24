-- 客户端自更新要校验下载的安装包是否完整：发布时记下 SHA-256。
-- 旧版本行保持 NULL，客户端退回只校验大小。
ALTER TABLE `app_versions`
  ADD COLUMN `sha256` VARCHAR(64) NULL AFTER `file_size`;
