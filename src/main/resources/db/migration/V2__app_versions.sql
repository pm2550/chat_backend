CREATE TABLE app_versions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  platform ENUM('ANDROID','IOS','WEB','MACOS','WINDOWS','HARMONY') NOT NULL,
  version_name VARCHAR(50) NOT NULL,
  version_code INT NOT NULL,
  force_update BIT NOT NULL DEFAULT 0,
  release_notes TEXT,
  download_url VARCHAR(500),
  artifact_filename VARCHAR(255),
  file_size BIGINT,
  is_active BIT NOT NULL DEFAULT 1,
  published_by BIGINT,
  created_at DATETIME(6),
  updated_at DATETIME(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_version_code (platform, version_code),
  KEY fk_app_version_publisher (published_by),
  CONSTRAINT fk_app_version_publisher FOREIGN KEY (published_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
