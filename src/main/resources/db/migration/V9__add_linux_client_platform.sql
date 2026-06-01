ALTER TABLE `device_tokens`
  MODIFY COLUMN `platform` enum('ANDROID','IOS','WEB','WINDOWS','MACOS','LINUX','HARMONY')
  COLLATE utf8mb4_unicode_ci NOT NULL;

ALTER TABLE `app_versions`
  MODIFY COLUMN `platform` enum('ANDROID','IOS','WEB','MACOS','WINDOWS','LINUX','HARMONY')
  NOT NULL;
