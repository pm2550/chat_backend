ALTER TABLE workspaces
  ADD COLUMN quota_bytes bigint DEFAULT 10737418240,
  ADD COLUMN used_bytes bigint DEFAULT 0;

ALTER TABLE workspace_folders
  ADD COLUMN deleted_at datetime(6) DEFAULT NULL,
  ADD COLUMN deleted_by bigint DEFAULT NULL,
  ADD KEY FK_workspace_folders_deleted_by (deleted_by),
  ADD CONSTRAINT FK_workspace_folders_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id);

ALTER TABLE workspace_files
  ADD COLUMN deleted_at datetime(6) DEFAULT NULL,
  ADD COLUMN deleted_by bigint DEFAULT NULL,
  ADD COLUMN scan_status enum('PENDING','CLEAN','BLOCKED','FAILED') DEFAULT 'CLEAN',
  ADD COLUMN scan_summary varchar(500) DEFAULT NULL,
  ADD COLUMN scanned_at datetime(6) DEFAULT NULL,
  ADD COLUMN storage_provider varchar(40) DEFAULT 'LOCAL',
  ADD COLUMN object_key varchar(500) DEFAULT NULL,
  ADD KEY FK_workspace_files_deleted_by (deleted_by),
  ADD CONSTRAINT FK_workspace_files_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id);

ALTER TABLE workspace_file_versions
  ADD COLUMN scan_status enum('PENDING','CLEAN','BLOCKED','FAILED') DEFAULT 'CLEAN',
  ADD COLUMN scan_summary varchar(500) DEFAULT NULL,
  ADD COLUMN scanned_at datetime(6) DEFAULT NULL,
  ADD COLUMN storage_provider varchar(40) DEFAULT 'LOCAL',
  ADD COLUMN object_key varchar(500) DEFAULT NULL;

UPDATE workspace_files
SET scan_status = 'CLEAN',
    scanned_at = COALESCE(updated_at, created_at, NOW(6)),
    storage_provider = 'LOCAL',
    object_key = current_storage_name
WHERE scan_status IS NULL OR object_key IS NULL;

UPDATE workspace_file_versions
SET scan_status = 'CLEAN',
    scanned_at = COALESCE(created_at, NOW(6)),
    storage_provider = 'LOCAL',
    object_key = storage_name
WHERE scan_status IS NULL OR object_key IS NULL;

UPDATE workspaces w
SET used_bytes = COALESCE((
  SELECT SUM(COALESCE(f.file_size, 0))
  FROM workspace_files f
  WHERE f.workspace_id = w.id AND COALESCE(f.is_deleted, 0) = 0
), 0)
WHERE used_bytes IS NULL OR used_bytes = 0;
