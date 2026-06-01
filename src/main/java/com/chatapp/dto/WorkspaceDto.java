package com.chatapp.dto;

import com.chatapp.entity.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkspaceDto {
    private Long id;
    private String name;
    private String description;
    private Workspace.WorkspaceType workspaceType;
    private Long ownerId;
    private String ownerName;
    private Boolean isLocked;
    private String lockReason;
    private Boolean botAccessEnabled;
    private Long quotaBytes;
    private Long usedBytes;
    private WorkspacePermission.AccessLevel myAccessLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkspaceDto fromEntity(Workspace workspace, WorkspacePermission.AccessLevel myAccessLevel) {
        WorkspaceDto dto = new WorkspaceDto();
        dto.setId(workspace.getId());
        dto.setName(workspace.getName());
        dto.setDescription(workspace.getDescription());
        dto.setWorkspaceType(workspace.getWorkspaceType());
        dto.setOwnerId(workspace.getOwner() != null ? workspace.getOwner().getId() : null);
        dto.setOwnerName(workspace.getOwner() != null ? workspace.getOwner().getDisplayName() : null);
        dto.setIsLocked(Boolean.TRUE.equals(workspace.getIsLocked()));
        dto.setLockReason(workspace.getLockReason());
        dto.setBotAccessEnabled(Boolean.TRUE.equals(workspace.getBotAccessEnabled()));
        dto.setQuotaBytes(workspace.getQuotaBytes());
        dto.setUsedBytes(workspace.getUsedBytes());
        dto.setMyAccessLevel(myAccessLevel);
        dto.setCreatedAt(workspace.getCreatedAt());
        dto.setUpdatedAt(workspace.getUpdatedAt());
        return dto;
    }

    @Data
    public static class CreateWorkspaceRequest {
        @NotBlank
        private String name;
        private String description;
        private Workspace.WorkspaceType workspaceType = Workspace.WorkspaceType.TEAM;
        private Boolean botAccessEnabled = false;
        private Long quotaBytes;
    }

    @Data
    public static class AddMemberRequest {
        @NotNull
        private Long userId;
        @NotNull
        private WorkspaceMember.WorkspaceRole role;
    }

    @Data
    public static class CreateFolderRequest {
        @NotBlank
        private String name;
        private Long parentFolderId;
        private Boolean botAccessEnabled = false;
    }

    @Data
    public static class LockRequest {
        private Boolean locked = true;
        private String reason;
    }

    @Data
    public static class GrantPermissionRequest {
        @NotNull
        private WorkspacePermission.ResourceType resourceType;
        private Long resourceId;
        @NotNull
        private WorkspacePermission.PrincipalType principalType;
        @NotNull
        private Long principalId;
        @NotNull
        private WorkspacePermission.AccessLevel accessLevel;
    }

    @Data
    public static class WorkspaceMemberDto {
        private Long id;
        private Long userId;
        private String username;
        private String displayName;
        private WorkspaceMember.WorkspaceRole role;
        private LocalDateTime createdAt;

        public static WorkspaceMemberDto fromEntity(WorkspaceMember member) {
            WorkspaceMemberDto dto = new WorkspaceMemberDto();
            dto.setId(member.getId());
            dto.setUserId(member.getUser() != null ? member.getUser().getId() : null);
            dto.setUsername(member.getUser() != null ? member.getUser().getUsername() : null);
            dto.setDisplayName(member.getUser() != null ? member.getUser().getDisplayName() : null);
            dto.setRole(member.getRole());
            dto.setCreatedAt(member.getCreatedAt());
            return dto;
        }
    }

    @Data
    public static class PermissionDto {
        private Long id;
        private Long workspaceId;
        private WorkspacePermission.ResourceType resourceType;
        private Long resourceId;
        private String resourceName;
        private WorkspacePermission.PrincipalType principalType;
        private Long principalId;
        private String principalName;
        private WorkspacePermission.AccessLevel accessLevel;
        private Long createdById;
        private String createdByName;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static PermissionDto fromEntity(
                WorkspacePermission permission,
                String resourceName,
                String principalName) {
            PermissionDto dto = new PermissionDto();
            dto.setId(permission.getId());
            dto.setWorkspaceId(permission.getWorkspace() != null ? permission.getWorkspace().getId() : null);
            dto.setResourceType(permission.getResourceType());
            dto.setResourceId(permission.getResourceId());
            dto.setResourceName(resourceName);
            dto.setPrincipalType(permission.getPrincipalType());
            dto.setPrincipalId(permission.getPrincipalId());
            dto.setPrincipalName(principalName);
            dto.setAccessLevel(permission.getAccessLevel());
            dto.setCreatedById(permission.getCreatedBy() != null ? permission.getCreatedBy().getId() : null);
            dto.setCreatedByName(permission.getCreatedBy() != null ? permission.getCreatedBy().getDisplayName() : null);
            dto.setCreatedAt(permission.getCreatedAt());
            dto.setUpdatedAt(permission.getUpdatedAt());
            return dto;
        }
    }

    @Data
    public static class FolderDto {
        private Long id;
        private Long workspaceId;
        private Long parentFolderId;
        private String name;
        private Boolean isLocked;
        private String lockReason;
        private Boolean botAccessEnabled;
        private Boolean isDeleted;
        private LocalDateTime deletedAt;
        private Long deletedById;
        private String deletedByName;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static FolderDto fromEntity(WorkspaceFolder folder) {
            FolderDto dto = new FolderDto();
            dto.setId(folder.getId());
            dto.setWorkspaceId(folder.getWorkspace() != null ? folder.getWorkspace().getId() : null);
            dto.setParentFolderId(folder.getParentFolder() != null ? folder.getParentFolder().getId() : null);
            dto.setName(folder.getName());
            dto.setIsLocked(Boolean.TRUE.equals(folder.getIsLocked()));
            dto.setLockReason(folder.getLockReason());
            dto.setBotAccessEnabled(Boolean.TRUE.equals(folder.getBotAccessEnabled()));
            dto.setIsDeleted(Boolean.TRUE.equals(folder.getIsDeleted()));
            dto.setDeletedAt(folder.getDeletedAt());
            dto.setDeletedById(folder.getDeletedBy() != null ? folder.getDeletedBy().getId() : null);
            dto.setDeletedByName(folder.getDeletedBy() != null ? folder.getDeletedBy().getDisplayName() : null);
            dto.setCreatedAt(folder.getCreatedAt());
            dto.setUpdatedAt(folder.getUpdatedAt());
            return dto;
        }
    }

    @Data
    public static class FileDto {
        private Long id;
        private Long workspaceId;
        private Long folderId;
        private String displayName;
        private String mimeType;
        private Long fileSize;
        private Integer currentVersion;
        private WorkspaceFile.SourceType sourceType;
        private Long sourceBotId;
        private String sourceBotName;
        private Long createdById;
        private String createdByName;
        private Boolean isLocked;
        private String lockReason;
        private Boolean botAccessEnabled;
        private Boolean isDeleted;
        private LocalDateTime deletedAt;
        private Long deletedById;
        private String deletedByName;
        private WorkspaceFile.ScanStatus scanStatus;
        private String scanSummary;
        private LocalDateTime scannedAt;
        private String storageProvider;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static FileDto fromEntity(WorkspaceFile file) {
            FileDto dto = new FileDto();
            dto.setId(file.getId());
            dto.setWorkspaceId(file.getWorkspace() != null ? file.getWorkspace().getId() : null);
            dto.setFolderId(file.getFolder() != null ? file.getFolder().getId() : null);
            dto.setDisplayName(file.getDisplayName());
            dto.setMimeType(file.getMimeType());
            dto.setFileSize(file.getFileSize());
            dto.setCurrentVersion(file.getCurrentVersion());
            dto.setSourceType(file.getSourceType());
            dto.setSourceBotId(file.getSourceBot() != null ? file.getSourceBot().getId() : null);
            dto.setSourceBotName(file.getSourceBot() != null ? file.getSourceBot().getBotName() : null);
            dto.setCreatedById(file.getCreatedBy() != null ? file.getCreatedBy().getId() : null);
            dto.setCreatedByName(file.getCreatedBy() != null ? file.getCreatedBy().getDisplayName() : null);
            dto.setIsLocked(Boolean.TRUE.equals(file.getIsLocked()));
            dto.setLockReason(file.getLockReason());
            dto.setBotAccessEnabled(Boolean.TRUE.equals(file.getBotAccessEnabled()));
            dto.setIsDeleted(Boolean.TRUE.equals(file.getIsDeleted()));
            dto.setDeletedAt(file.getDeletedAt());
            dto.setDeletedById(file.getDeletedBy() != null ? file.getDeletedBy().getId() : null);
            dto.setDeletedByName(file.getDeletedBy() != null ? file.getDeletedBy().getDisplayName() : null);
            dto.setScanStatus(file.getScanStatus());
            dto.setScanSummary(file.getScanSummary());
            dto.setScannedAt(file.getScannedAt());
            dto.setStorageProvider(file.getStorageProvider());
            dto.setCreatedAt(file.getCreatedAt());
            dto.setUpdatedAt(file.getUpdatedAt());
            return dto;
        }
    }

    @Data
    public static class VersionDto {
        private Long id;
        private Long fileId;
        private Integer versionNumber;
        private String originalName;
        private String mimeType;
        private Long fileSize;
        private String checksumSha256;
        private String versionNote;
        private WorkspaceFile.ScanStatus scanStatus;
        private String scanSummary;
        private LocalDateTime scannedAt;
        private String storageProvider;
        private Long uploadedById;
        private String uploadedByName;
        private Long uploadedByBotId;
        private String uploadedByBotName;
        private LocalDateTime createdAt;

        public static VersionDto fromEntity(WorkspaceFileVersion version) {
            VersionDto dto = new VersionDto();
            dto.setId(version.getId());
            dto.setFileId(version.getFile() != null ? version.getFile().getId() : null);
            dto.setVersionNumber(version.getVersionNumber());
            dto.setOriginalName(version.getOriginalName());
            dto.setMimeType(version.getMimeType());
            dto.setFileSize(version.getFileSize());
            dto.setChecksumSha256(version.getChecksumSha256());
            dto.setVersionNote(version.getVersionNote());
            dto.setScanStatus(version.getScanStatus());
            dto.setScanSummary(version.getScanSummary());
            dto.setScannedAt(version.getScannedAt());
            dto.setStorageProvider(version.getStorageProvider());
            dto.setUploadedById(version.getUploadedBy() != null ? version.getUploadedBy().getId() : null);
            dto.setUploadedByName(version.getUploadedBy() != null ? version.getUploadedBy().getDisplayName() : null);
            dto.setUploadedByBotId(version.getUploadedByBot() != null ? version.getUploadedByBot().getId() : null);
            dto.setUploadedByBotName(version.getUploadedByBot() != null ? version.getUploadedByBot().getBotName() : null);
            dto.setCreatedAt(version.getCreatedAt());
            return dto;
        }
    }

    @Data
    public static class ContentsResponse {
        private final List<FolderDto> folders;
        private final List<FileDto> files;
    }

    @Data
    public static class TrashResponse {
        private final List<FolderDto> folders;
        private final List<FileDto> files;
    }

    @Data
    public static class MaintenanceResult {
        private final Integer orphanCount;
        private final Integer deletedCount;
        private final Long bytes;
        private final Boolean dryRun;
        private final List<String> fileNames;
    }

    @Data
    public static class DownloadedWorkspaceFile {
        private final WorkspaceFile file;
        private final byte[] bytes;
    }
}
