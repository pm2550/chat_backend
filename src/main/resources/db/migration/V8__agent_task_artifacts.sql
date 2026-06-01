ALTER TABLE agent_tasks
  ADD COLUMN artifact_workspace_id bigint DEFAULT NULL,
  ADD COLUMN artifact_folder_id bigint DEFAULT NULL,
  ADD COLUMN artifact_file_id bigint DEFAULT NULL,
  ADD COLUMN artifact_file_name varchar(255) DEFAULT NULL;
