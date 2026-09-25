-- Android 安装包按 CPU 架构拆包（arm64-v8a / armeabi-v7a 各一个 APK）：
-- 同一个 Android 版本号下会有两行，唯一键要带上 abi。
-- abi = '' 表示不分架构的整包（旧发布、以及所有非 Android 平台），老行自动落到这里。
-- 用 NOT NULL DEFAULT '' 而不是 NULL：MySQL 唯一键里 NULL 互不相等，
-- 可空的话同一平台同一版本号就能插出两条整包行，发布幂等就失去了数据库兜底。
ALTER TABLE `app_versions`
  ADD COLUMN `abi` VARCHAR(32) NOT NULL DEFAULT '' AFTER `platform`;

-- 旧唯一键 (platform, version_code) 由 V2 建成 uk_platform_version_code；
-- 按列查出实际的索引名再删，避免库里名字不同导致迁移失败、服务起不来。
SET @old_uk := (
  SELECT INDEX_NAME
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'app_versions'
    AND NON_UNIQUE = 0
    AND INDEX_NAME <> 'PRIMARY'
  GROUP BY INDEX_NAME
  HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'platform,version_code'
  LIMIT 1
);
SET @drop_old_uk := IF(@old_uk IS NULL,
  'SELECT 1',
  CONCAT('ALTER TABLE `app_versions` DROP INDEX `', @old_uk, '`'));
PREPARE stmt FROM @drop_old_uk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `app_versions`
  ADD UNIQUE KEY `uk_platform_version_code_abi` (`platform`, `version_code`, `abi`);
