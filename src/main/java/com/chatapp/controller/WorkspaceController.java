package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.User;
import com.chatapp.entity.WorkspaceFile;
import com.chatapp.service.UserService;
import com.chatapp.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkspaceDto>>> list(Authentication auth) {
        Long currentUserId = currentUserId(auth);
        return ResponseEntity.ok(ApiResponse.success(workspaceService.listWorkspaces(currentUserId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceDto>> create(
            @Valid @RequestBody WorkspaceDto.CreateWorkspaceRequest request,
            Authentication auth) {
        Long currentUserId = currentUserId(auth);
        return ResponseEntity.ok(ApiResponse.success("工作区已创建",
                workspaceService.createWorkspace(currentUserId, request)));
    }

    @GetMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<WorkspaceDto>> get(
            @PathVariable Long workspaceId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.getWorkspace(workspaceId, currentUserId(auth))));
    }

    @PutMapping("/{workspaceId}/lock")
    public ResponseEntity<ApiResponse<WorkspaceDto>> lockWorkspace(
            @PathVariable Long workspaceId,
            @RequestBody(required = false) WorkspaceDto.LockRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.setWorkspaceLock(workspaceId, currentUserId(auth), request)));
    }

    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<ApiResponse<List<WorkspaceDto.WorkspaceMemberDto>>> members(
            @PathVariable Long workspaceId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.listMembers(workspaceId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/members")
    public ResponseEntity<ApiResponse<WorkspaceDto.WorkspaceMemberDto>> addMember(
            @PathVariable Long workspaceId,
            @Valid @RequestBody WorkspaceDto.AddMemberRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("成员权限已更新",
                workspaceService.addMember(workspaceId, currentUserId(auth), request)));
    }

    @GetMapping("/{workspaceId}/permissions")
    public ResponseEntity<ApiResponse<List<WorkspaceDto.PermissionDto>>> permissions(
            @PathVariable Long workspaceId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.listPermissions(workspaceId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/permissions")
    public ResponseEntity<ApiResponse<WorkspaceDto.PermissionDto>> grantPermission(
            @PathVariable Long workspaceId,
            @Valid @RequestBody WorkspaceDto.GrantPermissionRequest request,
            Authentication auth) {
        WorkspaceDto.PermissionDto permission = workspaceService.grantPermission(
                workspaceId,
                currentUserId(auth),
                request);
        return ResponseEntity.ok(ApiResponse.success("权限已更新", permission));
    }

    @DeleteMapping("/{workspaceId}/permissions/{permissionId}")
    public ResponseEntity<ApiResponse<Void>> revokePermission(
            @PathVariable Long workspaceId,
            @PathVariable Long permissionId,
            Authentication auth) {
        workspaceService.revokePermission(workspaceId, permissionId, currentUserId(auth));
        return ResponseEntity.ok(ApiResponse.success("权限已撤销", null));
    }

    @GetMapping("/{workspaceId}/contents")
    public ResponseEntity<ApiResponse<WorkspaceDto.ContentsResponse>> contents(
            @PathVariable Long workspaceId,
            @RequestParam(required = false) Long folderId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.listContents(workspaceId, currentUserId(auth), folderId)));
    }

    @PostMapping("/{workspaceId}/folders")
    public ResponseEntity<ApiResponse<WorkspaceDto.FolderDto>> createFolder(
            @PathVariable Long workspaceId,
            @Valid @RequestBody WorkspaceDto.CreateFolderRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件夹已创建",
                workspaceService.createFolder(workspaceId, currentUserId(auth), request)));
    }

    @PutMapping("/{workspaceId}/folders/{folderId}/lock")
    public ResponseEntity<ApiResponse<WorkspaceDto.FolderDto>> lockFolder(
            @PathVariable Long workspaceId,
            @PathVariable Long folderId,
            @RequestBody(required = false) WorkspaceDto.LockRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.setFolderLock(workspaceId, folderId, currentUserId(auth), request)));
    }

    @DeleteMapping("/{workspaceId}/folders/{folderId}")
    public ResponseEntity<ApiResponse<WorkspaceDto.FolderDto>> deleteFolder(
            @PathVariable Long workspaceId,
            @PathVariable Long folderId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件夹已移入回收站",
                workspaceService.softDeleteFolder(workspaceId, folderId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/folders/{folderId}/restore")
    public ResponseEntity<ApiResponse<WorkspaceDto.FolderDto>> restoreFolder(
            @PathVariable Long workspaceId,
            @PathVariable Long folderId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件夹已恢复",
                workspaceService.restoreFolder(workspaceId, folderId, currentUserId(auth))));
    }

    @PostMapping(value = "/{workspaceId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> uploadFile(
            @PathVariable Long workspaceId,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) Long sourceBotId,
            @RequestParam(required = false) String versionNote,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success("文件已保存",
                workspaceService.uploadFile(workspaceId, currentUserId(auth), folderId, sourceBotId, versionNote, file)));
    }

    @GetMapping("/{workspaceId}/files/{fileId}/download")
    public ResponseEntity<ByteArrayResource> downloadFile(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) throws IOException {
        WorkspaceDto.DownloadedWorkspaceFile download = workspaceService.downloadFile(
                workspaceId,
                fileId,
                currentUserId(auth));
        WorkspaceFile file = download.getFile();
        ByteArrayResource resource = new ByteArrayResource(download.getBytes());
        String contentType = file.getMimeType() != null && !file.getMimeType().isBlank()
                ? file.getMimeType()
                : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getDisplayName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    @GetMapping("/{workspaceId}/files/{fileId}/preview")
    public ResponseEntity<ByteArrayResource> previewFile(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) throws IOException {
        WorkspaceDto.DownloadedWorkspaceFile download = workspaceService.previewFile(
                workspaceId,
                fileId,
                currentUserId(auth));
        WorkspaceFile file = download.getFile();
        ByteArrayResource resource = new ByteArrayResource(download.getBytes());
        String contentType = file.getMimeType() != null && !file.getMimeType().isBlank()
                ? file.getMimeType()
                : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(file.getDisplayName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(resource);
    }

    @GetMapping("/{workspaceId}/files/{fileId}/versions")
    public ResponseEntity<ApiResponse<List<WorkspaceDto.VersionDto>>> versions(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.listVersions(workspaceId, fileId, currentUserId(auth))));
    }

    @PostMapping(value = "/{workspaceId}/files/{fileId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> addVersion(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            @RequestParam(required = false) Long sourceBotId,
            @RequestParam(required = false) String versionNote,
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success("文件版本已保存",
                workspaceService.addFileVersion(workspaceId, fileId, currentUserId(auth), sourceBotId, versionNote, file)));
    }

    // ---- F6: in-app text editing ----

    @GetMapping("/{workspaceId}/files/{fileId}/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.TextContent>> readText(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.readTextContent(workspaceId, fileId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/files/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> createTextFile(
            @PathVariable Long workspaceId,
            @Valid @RequestBody WorkspaceDto.CreateTextFileRequest request,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success("文件已创建",
                workspaceService.createTextFile(workspaceId, currentUserId(auth), request.getFolderId(),
                        request.getSourceBotId(), request.getFileName(), request.getContent(),
                        request.getVersionNote())));
    }

    @PostMapping("/{workspaceId}/files/{fileId}/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> saveText(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            @RequestBody WorkspaceDto.SaveTextRequest request,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success("文件已保存",
                workspaceService.saveTextVersion(workspaceId, fileId, currentUserId(auth),
                        request.getSourceBotId(), request.getContent(), request.getVersionNote())));
    }

    @PostMapping("/{workspaceId}/files/{fileId}/versions/{versionNumber}/restore")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> restoreVersion(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            @PathVariable Integer versionNumber,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件版本已恢复",
                workspaceService.restoreVersion(workspaceId, fileId, versionNumber, currentUserId(auth))));
    }

    @PutMapping("/{workspaceId}/files/{fileId}/lock")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> lockFile(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            @RequestBody(required = false) WorkspaceDto.LockRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.setFileLock(workspaceId, fileId, currentUserId(auth), request)));
    }

    @DeleteMapping("/{workspaceId}/files/{fileId}")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> deleteFile(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件已移入回收站",
                workspaceService.softDeleteFile(workspaceId, fileId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/files/{fileId}/restore")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> restoreFile(
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("文件已恢复",
                workspaceService.restoreFile(workspaceId, fileId, currentUserId(auth))));
    }

    @GetMapping("/{workspaceId}/trash")
    public ResponseEntity<ApiResponse<WorkspaceDto.TrashResponse>> trash(
            @PathVariable Long workspaceId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                workspaceService.listTrash(workspaceId, currentUserId(auth))));
    }

    @PostMapping("/{workspaceId}/maintenance/orphans")
    public ResponseEntity<ApiResponse<WorkspaceDto.MaintenanceResult>> cleanupOrphans(
            @PathVariable Long workspaceId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            Authentication auth) throws IOException {
        return ResponseEntity.ok(ApiResponse.success(
                dryRun ? "孤儿文件扫描完成" : "孤儿文件清理完成",
                workspaceService.cleanupOrphanWorkspaceFiles(workspaceId, currentUserId(auth), dryRun)));
    }

    private Long currentUserId(Authentication auth) {
        UserDto dto = userService.findByUsername(auth.getName());
        return dto.getId();
    }
}
