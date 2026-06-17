package com.chatapp.service;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.*;
import com.chatapp.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final long DEFAULT_WORKSPACE_QUOTA_BYTES = 10L * 1024 * 1024 * 1024;
    /** Cap for in-app text read/edit (F6); larger files must use upload/download. */
    private static final int MAX_EDITABLE_TEXT_BYTES = 2 * 1024 * 1024;

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceFolderRepository workspaceFolderRepository;
    private final WorkspaceFileRepository workspaceFileRepository;
    private final WorkspaceFileVersionRepository workspaceFileVersionRepository;
    private final WorkspacePermissionRepository workspacePermissionRepository;
    private final UserRepository userRepository;
    private final BotConfigRepository botConfigRepository;
    private final FileStorageService fileStorageService;
    private final WorkspaceFileScanService workspaceFileScanService;
    private final AuditLogService auditLogService;

    @Transactional
    public WorkspaceDto createWorkspace(Long actorId, WorkspaceDto.CreateWorkspaceRequest request) {
        User actor = getUser(actorId);
        Workspace workspace = new Workspace();
        workspace.setName(cleanName(request.getName(), "资料库名称不能为空"));
        workspace.setDescription(request.getDescription());
        workspace.setWorkspaceType(request.getWorkspaceType() != null
                ? request.getWorkspaceType()
                : Workspace.WorkspaceType.TEAM);
        workspace.setOwner(actor);
        workspace.setCreatedBy(actor);
        workspace.setBotAccessEnabled(Boolean.TRUE.equals(request.getBotAccessEnabled()));
        workspace.setQuotaBytes(request.getQuotaBytes() != null && request.getQuotaBytes() > 0
                ? request.getQuotaBytes()
                : DEFAULT_WORKSPACE_QUOTA_BYTES);
        workspace.setUsedBytes(0L);
        Workspace saved = workspaceRepository.save(workspace);

        WorkspaceMember ownerMember = new WorkspaceMember();
        ownerMember.setWorkspace(saved);
        ownerMember.setUser(actor);
        ownerMember.setRole(WorkspaceMember.WorkspaceRole.OWNER);
        ownerMember.setInvitedBy(actor);
        workspaceMemberRepository.save(ownerMember);

        auditLogService.record(actor, "WORKSPACE_CREATE", "WORKSPACE", saved.getId(), null, saved.getName());
        return WorkspaceDto.fromEntity(saved, WorkspacePermission.AccessLevel.MANAGE);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> listWorkspaces(Long actorId) {
        Map<Long, Workspace> workspaces = new LinkedHashMap<>();
        workspaceRepository.findByOwnerIdAndIsActiveTrueOrderByUpdatedAtDesc(actorId)
                .forEach(workspace -> workspaces.put(workspace.getId(), workspace));
        workspaceMemberRepository.findByUserId(actorId).stream()
                .map(WorkspaceMember::getWorkspace)
                .filter(workspace -> Boolean.TRUE.equals(workspace.getIsActive()))
                .forEach(workspace -> workspaces.putIfAbsent(workspace.getId(), workspace));
        workspacePermissionRepository.findByPrincipalTypeAndPrincipalId(
                        WorkspacePermission.PrincipalType.USER,
                        actorId)
                .stream()
                .map(WorkspacePermission::getWorkspace)
                .filter(workspace -> Boolean.TRUE.equals(workspace.getIsActive()))
                .forEach(workspace -> workspaces.putIfAbsent(workspace.getId(), workspace));

        return workspaces.values().stream()
                .sorted(Comparator.comparing(Workspace::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(workspace -> WorkspaceDto.fromEntity(workspace, effectiveUserAccess(workspace, actorId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> listWorkspacesForBot(Long botId) {
        Map<Long, Workspace> workspaces = new LinkedHashMap<>();
        workspacePermissionRepository.findByPrincipalTypeAndPrincipalId(
                        WorkspacePermission.PrincipalType.BOT,
                        botId)
                .stream()
                .map(WorkspacePermission::getWorkspace)
                .filter(workspace -> workspace != null && Boolean.TRUE.equals(workspace.getIsActive()))
                .filter(workspace -> botGateOpen(workspace, botId))
                .forEach(workspace -> workspaces.putIfAbsent(workspace.getId(), workspace));

        return workspaces.values().stream()
                .sorted(Comparator.comparing(Workspace::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(workspace -> WorkspaceDto.fromEntity(workspace, effectiveBotAccess(workspace, botId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDto getWorkspace(Long workspaceId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        requireWorkspaceVisible(workspace, actorId);
        return WorkspaceDto.fromEntity(workspace, effectiveUserAccess(workspace, actorId));
    }

    @Transactional
    public WorkspaceDto setWorkspaceLock(Long workspaceId, Long actorId, WorkspaceDto.LockRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        boolean locked = request == null || !Boolean.FALSE.equals(request.getLocked());
        workspace.setIsLocked(locked);
        workspace.setLockReason(locked && request != null ? request.getReason() : null);
        workspace.setLockedBy(locked ? actor : null);
        auditLogService.record(actor, locked ? "WORKSPACE_LOCK" : "WORKSPACE_UNLOCK",
                "WORKSPACE", workspace.getId(), null, workspace.getName());
        return WorkspaceDto.fromEntity(workspaceRepository.save(workspace), WorkspacePermission.AccessLevel.MANAGE);
    }

    @Transactional
    public WorkspaceDto.WorkspaceMemberDto addMember(
            Long workspaceId,
            Long actorId,
            WorkspaceDto.AddMemberRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        User memberUser = getUser(request.getUserId());
        WorkspaceMember.WorkspaceRole role = request.getRole() != null
                ? request.getRole()
                : WorkspaceMember.WorkspaceRole.VIEWER;
        WorkspaceMember member = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, memberUser.getId())
                .orElseGet(WorkspaceMember::new);
        member.setWorkspace(workspace);
        member.setUser(memberUser);
        member.setRole(role);
        member.setInvitedBy(actor);
        WorkspaceMember saved = workspaceMemberRepository.save(member);
        auditLogService.record(actor, "WORKSPACE_MEMBER_UPSERT", "WORKSPACE",
                workspaceId, null, memberUser.getUsername() + ":" + role.name());
        return WorkspaceDto.WorkspaceMemberDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto.WorkspaceMemberDto> listMembers(Long workspaceId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.VIEW);
        return workspaceMemberRepository.findByWorkspaceIdOrderByRoleAscCreatedAtAsc(workspaceId)
                .stream()
                .map(WorkspaceDto.WorkspaceMemberDto::fromEntity)
                .toList();
    }

    @Transactional
    public WorkspaceDto.FolderDto createFolder(
            Long workspaceId,
            Long actorId,
            WorkspaceDto.CreateFolderRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        requireWritable(workspace, null, null, actorId);
        WorkspaceFolder parent = null;
        if (request.getParentFolderId() != null) {
            parent = loadFolder(workspaceId, request.getParentFolderId());
            requireWritable(workspace, parent, null, actorId);
        }

        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setWorkspace(workspace);
        folder.setParentFolder(parent);
        folder.setName(cleanName(request.getName(), "文件夹名称不能为空"));
        folder.setCreatedBy(actor);
        folder.setBotAccessEnabled(Boolean.TRUE.equals(request.getBotAccessEnabled()));
        WorkspaceFolder saved = workspaceFolderRepository.save(folder);
        auditLogService.record(actor, "WORKSPACE_FOLDER_CREATE", "WORKSPACE_FOLDER",
                saved.getId(), null, saved.getName());
        return WorkspaceDto.FolderDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.ContentsResponse listContents(Long workspaceId, Long actorId, Long folderId) {
        Workspace workspace = loadWorkspace(workspaceId);
        requireWorkspaceVisible(workspace, actorId);
        WorkspaceFolder folder = null;
        if (folderId != null) {
            folder = loadFolder(workspaceId, folderId);
            requireUserResourceAccess(workspace, folder.getId(),
                    WorkspacePermission.ResourceType.FOLDER,
                    actorId,
                    WorkspacePermission.AccessLevel.VIEW);
        }
        List<WorkspaceFolder> folders = folder == null
                ? workspaceFolderRepository.findByWorkspaceIdAndParentFolderIsNullAndIsDeletedFalseOrderByNameAsc(workspaceId)
                : workspaceFolderRepository.findByWorkspaceIdAndParentFolderIdAndIsDeletedFalseOrderByNameAsc(workspaceId, folder.getId());
        List<WorkspaceFile> files = folder == null
                ? workspaceFileRepository.findByWorkspaceIdAndFolderIsNullAndIsDeletedFalseOrderByUpdatedAtDesc(workspaceId)
                : workspaceFileRepository.findByWorkspaceIdAndFolderIdAndIsDeletedFalseOrderByUpdatedAtDesc(workspaceId, folder.getId());
        return new WorkspaceDto.ContentsResponse(
                folders.stream()
                        .filter(candidate -> hasUserResourceAccess(workspace, candidate.getId(),
                                WorkspacePermission.ResourceType.FOLDER, actorId, WorkspacePermission.AccessLevel.VIEW))
                        .map(WorkspaceDto.FolderDto::fromEntity)
                        .toList(),
                files.stream()
                        .filter(candidate -> hasUserResourceAccess(workspace, candidate.getId(),
                                WorkspacePermission.ResourceType.FILE, actorId, WorkspacePermission.AccessLevel.VIEW))
                        .map(WorkspaceDto.FileDto::fromEntity)
                        .toList());
    }

    @Transactional
    public WorkspaceDto.FolderDto setFolderLock(
            Long workspaceId,
            Long folderId,
            Long actorId,
            WorkspaceDto.LockRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFolder folder = loadFolder(workspaceId, folderId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        boolean locked = request == null || !Boolean.FALSE.equals(request.getLocked());
        folder.setIsLocked(locked);
        folder.setLockReason(locked && request != null ? request.getReason() : null);
        folder.setLockedBy(locked ? actor : null);
        WorkspaceFolder saved = workspaceFolderRepository.save(folder);
        auditLogService.record(actor, locked ? "WORKSPACE_FOLDER_LOCK" : "WORKSPACE_FOLDER_UNLOCK",
                "WORKSPACE_FOLDER", folderId, null, folder.getName());
        return WorkspaceDto.FolderDto.fromEntity(saved);
    }

    @Transactional
    public WorkspaceDto.FileDto uploadFile(
            Long workspaceId,
            Long actorId,
            Long folderId,
            Long sourceBotId,
            String versionNote,
            MultipartFile multipartFile) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireWritable(workspace, folder, null, actorId);
        BotConfig sourceBot = resolveSourceBot(sourceBotId, actorId, workspace);
        byte[] bytes = multipartFile.getBytes();
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(multipartFile, bytes);
        assertWithinQuota(workspace, multipartFile.getSize());

        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                safeOriginalName(multipartFile),
                multipartFile.getContentType(),
                bytes);

        WorkspaceFile file = new WorkspaceFile();
        file.setWorkspace(workspace);
        file.setFolder(folder);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCreatedBy(actor);
        file.setSourceBot(sourceBot);
        file.setSourceType(sourceBot != null ? WorkspaceFile.SourceType.BOT : WorkspaceFile.SourceType.USER);
        file.setBotAccessEnabled(false);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile savedFile = workspaceFileRepository.save(file);
        saveVersion(savedFile, 1, storedFile, bytes, actor, sourceBot, versionNote, scan);
        adjustWorkspaceUsage(workspace, storedFile.size());
        auditLogService.record(actor, "WORKSPACE_FILE_UPLOAD", "WORKSPACE_FILE",
                savedFile.getId(), null, savedFile.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(savedFile);
    }

    @Transactional
    public WorkspaceDto.FileDto addFileVersion(
            Long workspaceId,
            Long fileId,
            Long actorId,
            Long sourceBotId,
            String versionNote,
            MultipartFile multipartFile) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        User actor = getUser(actorId);
        requireWritable(workspace, file.getFolder(), file, actorId);
        BotConfig sourceBot = resolveSourceBot(sourceBotId, actorId, workspace);
        byte[] bytes = multipartFile.getBytes();
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(multipartFile, bytes);
        long delta = multipartFile.getSize() - safeSize(file.getFileSize());
        assertWithinQuota(workspace, delta);

        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                safeOriginalName(multipartFile),
                multipartFile.getContentType(),
                bytes);
        int nextVersion = Optional.ofNullable(file.getCurrentVersion()).orElse(0) + 1;
        saveVersion(file, nextVersion, storedFile, bytes, actor, sourceBot, versionNote, scan);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCurrentVersion(nextVersion);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, delta);
        auditLogService.record(actor, "WORKSPACE_FILE_VERSION_ADD", "WORKSPACE_FILE",
                saved.getId(), null, saved.getDisplayName() + "#" + nextVersion);
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional
    public WorkspaceDto.FileDto saveGeneratedFile(
            Long workspaceId,
            Long actorId,
            Long folderId,
            Long sourceBotId,
            String fileName,
            String contentType,
            byte[] bytes,
            String versionNote) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireWritable(workspace, folder, null, actorId);
        BotConfig sourceBot = resolveSourceBot(sourceBotId, actorId, workspace);
        String safeFileName = generatedFileName(fileName);
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(safeFileName, safeBytes);
        assertWithinQuota(workspace, safeBytes.length);

        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                safeFileName,
                contentType == null || contentType.isBlank() ? "text/plain" : contentType,
                safeBytes);

        WorkspaceFile file = new WorkspaceFile();
        file.setWorkspace(workspace);
        file.setFolder(folder);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCreatedBy(actor);
        file.setSourceBot(sourceBot);
        file.setSourceType(sourceBot != null ? WorkspaceFile.SourceType.BOT : WorkspaceFile.SourceType.SERVICE);
        file.setBotAccessEnabled(false);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile savedFile = workspaceFileRepository.save(file);
        saveVersion(savedFile, 1, storedFile, safeBytes, actor, sourceBot, versionNote, scan);
        adjustWorkspaceUsage(workspace, storedFile.size());
        auditLogService.record(actor, "WORKSPACE_FILE_GENERATED", "WORKSPACE_FILE",
                savedFile.getId(), null, savedFile.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(savedFile);
    }

    // ---- F6: in-app text editing (read current text / create text file / save edited version) ----

    /** Read the current version of a text-like file as a UTF-8 string for in-app editing. */
    @Transactional(readOnly = true)
    public WorkspaceDto.TextContent readTextContent(Long workspaceId, Long fileId, Long actorId)
            throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireUserResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                actorId, WorkspacePermission.AccessLevel.VIEW);
        if (!isPreviewable(file)) {
            throw new IllegalArgumentException("该文件不是可在线编辑的文本类型");
        }
        if (safeSize(file.getFileSize()) > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文件过大，无法在线编辑（上限 "
                    + (MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)) + "MB）");
        }
        byte[] bytes = fileStorageService.getWorkspaceFile(file.getStorageProvider(), storageKey(file));
        return new WorkspaceDto.TextContent(
                file.getId(), file.getDisplayName(), file.getMimeType(),
                file.getCurrentVersion(), new String(bytes, StandardCharsets.UTF_8));
    }

    /** Create a new text file (version 1) from a UTF-8 string. */
    @Transactional
    public WorkspaceDto.FileDto createTextFile(Long workspaceId, Long actorId, Long folderId,
            Long sourceBotId, String fileName, String content, String versionNote) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireWritable(workspace, folder, null, actorId);
        BotConfig sourceBot = resolveSourceBot(sourceBotId, actorId, workspace);
        String safeFileName = generatedFileName(fileName);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文本内容过大（上限 "
                    + (MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)) + "MB）");
        }
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(safeFileName, bytes);
        assertWithinQuota(workspace, bytes.length);

        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                safeFileName, "text/plain; charset=utf-8", bytes);

        WorkspaceFile file = new WorkspaceFile();
        file.setWorkspace(workspace);
        file.setFolder(folder);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCreatedBy(actor);
        file.setSourceBot(sourceBot);
        file.setSourceType(sourceBot != null ? WorkspaceFile.SourceType.BOT : WorkspaceFile.SourceType.USER);
        file.setBotAccessEnabled(false);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile savedFile = workspaceFileRepository.save(file);
        saveVersion(savedFile, 1, storedFile, bytes, actor, sourceBot, versionNote, scan);
        adjustWorkspaceUsage(workspace, storedFile.size());
        auditLogService.record(actor, "WORKSPACE_FILE_TEXT_CREATE", "WORKSPACE_FILE",
                savedFile.getId(), null, savedFile.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(savedFile);
    }

    /** Save edited text as a new version of an existing file (preserves the display name). */
    @Transactional
    public WorkspaceDto.FileDto saveTextVersion(Long workspaceId, Long fileId, Long actorId,
            Long sourceBotId, String content, String versionNote) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        User actor = getUser(actorId);
        requireWritable(workspace, file.getFolder(), file, actorId);
        BotConfig sourceBot = resolveSourceBot(sourceBotId, actorId, workspace);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文本内容过大（上限 "
                    + (MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)) + "MB）");
        }
        String fileName = file.getDisplayName() == null || file.getDisplayName().isBlank()
                ? "edited.txt" : file.getDisplayName();
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(fileName, bytes);
        long delta = bytes.length - safeSize(file.getFileSize());
        assertWithinQuota(workspace, delta);

        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                fileName,
                file.getMimeType() == null || file.getMimeType().isBlank()
                        ? "text/plain; charset=utf-8" : file.getMimeType(),
                bytes);
        int nextVersion = Optional.ofNullable(file.getCurrentVersion()).orElse(0) + 1;
        saveVersion(file, nextVersion, storedFile, bytes, actor, sourceBot, versionNote, scan);
        // Editing keeps the original display name (unlike a multipart re-upload).
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setFileSize(storedFile.size());
        file.setCurrentVersion(nextVersion);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, delta);
        auditLogService.record(actor, "WORKSPACE_FILE_TEXT_EDIT", "WORKSPACE_FILE",
                saved.getId(), null, saved.getDisplayName() + "#" + nextVersion);
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    // ---- F6 slice 2: bot-principal access + file ops (backs the agent workspace tools) ----

    /**
     * Effective access a BOT has on a workspace. Unlike users, a bot is never an owner or
     * member — its only access source is an explicit {@link WorkspacePermission} with
     * {@link WorkspacePermission.PrincipalType#BOT}, and only when the workspace has
     * botAccessEnabled. An inactive/unknown bot (or a bot-access-disabled workspace) gets NONE.
     */
    public WorkspacePermission.AccessLevel effectiveBotAccess(Workspace workspace, Long botId) {
        if (!botGateOpen(workspace, botId)) {
            return WorkspacePermission.AccessLevel.NONE;
        }
        return explicitAccess(workspace.getId(), WorkspacePermission.ResourceType.WORKSPACE,
                null, WorkspacePermission.PrincipalType.BOT, botId);
    }

    /**
     * Master bot-access gate: the bot must exist + be active AND the workspace must have
     * botAccessEnabled. ALL bot access (workspace- and resource-level) is denied unless this
     * is open — so an explicit grant can never bypass a disabled botAccessEnabled flag.
     */
    private boolean botGateOpen(Workspace workspace, Long botId) {
        if (botId == null) {
            return false;
        }
        BotConfig bot = botConfigRepository.findById(botId).orElse(null);
        if (bot == null || Boolean.FALSE.equals(bot.getIsActive())) {
            return false;
        }
        return Boolean.TRUE.equals(workspace.getBotAccessEnabled());
    }

    private boolean hasBotResourceAccess(Workspace workspace, Long resourceId,
            WorkspacePermission.ResourceType resourceType, Long botId,
            WorkspacePermission.AccessLevel required) {
        if (!botGateOpen(workspace, botId)) {
            return false; // master gate — never escalate past a disabled flag / inactive bot
        }
        WorkspacePermission.AccessLevel level = explicitAccess(workspace.getId(),
                WorkspacePermission.ResourceType.WORKSPACE, null,
                WorkspacePermission.PrincipalType.BOT, botId);
        if (resourceType != WorkspacePermission.ResourceType.WORKSPACE) {
            level = max(level, explicitAccess(workspace.getId(), resourceType, resourceId,
                    WorkspacePermission.PrincipalType.BOT, botId));
        }
        return level.allows(required);
    }

    private void requireBotResourceAccess(Workspace workspace, Long resourceId,
            WorkspacePermission.ResourceType resourceType, Long botId,
            WorkspacePermission.AccessLevel required) {
        if (!hasBotResourceAccess(workspace, resourceId, resourceType, botId, required)) {
            throw new AccessDeniedException("机器人没有该工作区资源的权限");
        }
    }

    private BotConfig requireBotWithOwner(Long botId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot 不存在"));
        if (bot.getCreatedBy() == null) {
            // created_by / uploaded_by are NOT NULL FKs to users; a bot file is attributed
            // to the bot's owner (sourceBot records that the bot authored it).
            throw new IllegalStateException("Bot 缺少归属用户，无法写入工作区");
        }
        return bot;
    }

    /** Bot write gate: EDIT on the target resource + no lock bypass (bots never bypass locks). */
    private void requireBotWritable(Workspace workspace, WorkspaceFolder folder, WorkspaceFile file, Long botId) {
        WorkspacePermission.ResourceType type = file != null ? WorkspacePermission.ResourceType.FILE
                : folder != null ? WorkspacePermission.ResourceType.FOLDER
                : WorkspacePermission.ResourceType.WORKSPACE;
        Long resourceId = file != null ? file.getId() : folder != null ? folder.getId() : null;
        requireBotResourceAccess(workspace, resourceId, type, botId, WorkspacePermission.AccessLevel.EDIT);
        if (Boolean.TRUE.equals(workspace.getIsLocked())) {
            throw new AccessDeniedException("工作区已锁定，机器人无法写入");
        }
        if (folder != null && Boolean.TRUE.equals(folder.getIsLocked())) {
            throw new AccessDeniedException("文件夹已锁定");
        }
        if (file != null && Boolean.TRUE.equals(file.getIsLocked())) {
            throw new AccessDeniedException("文件已锁定");
        }
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto.FileDto> listFilesForBot(Long workspaceId, Long botId, Long folderId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireBotResourceAccess(workspace, folder != null ? folder.getId() : null,
                folder != null ? WorkspacePermission.ResourceType.FOLDER : WorkspacePermission.ResourceType.WORKSPACE,
                botId, WorkspacePermission.AccessLevel.VIEW);
        List<WorkspaceFile> files = folder == null
                ? workspaceFileRepository.findByWorkspaceIdAndFolderIsNullAndIsDeletedFalseOrderByUpdatedAtDesc(workspaceId)
                : workspaceFileRepository.findByWorkspaceIdAndFolderIdAndIsDeletedFalseOrderByUpdatedAtDesc(workspaceId, folder.getId());
        return files.stream()
                .filter(f -> hasBotResourceAccess(workspace, f.getId(),
                        WorkspacePermission.ResourceType.FILE, botId, WorkspacePermission.AccessLevel.VIEW))
                .map(WorkspaceDto.FileDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.TextContent readTextForBot(Long workspaceId, Long fileId, Long botId) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireBotResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                botId, WorkspacePermission.AccessLevel.VIEW);
        if (!isPreviewable(file)) {
            throw new IllegalArgumentException("该文件不是可读取的文本类型");
        }
        if (safeSize(file.getFileSize()) > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文件过大，无法读取为文本");
        }
        byte[] bytes = fileStorageService.getWorkspaceFile(file.getStorageProvider(), storageKey(file));
        return new WorkspaceDto.TextContent(file.getId(), file.getDisplayName(), file.getMimeType(),
                file.getCurrentVersion(), new String(bytes, StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.DownloadedWorkspaceFile downloadFileForBot(Long workspaceId, Long fileId, Long botId) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireBotResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                botId, WorkspacePermission.AccessLevel.VIEW);
        byte[] bytes = fileStorageService.getWorkspaceFile(file.getStorageProvider(), storageKey(file));
        return new WorkspaceDto.DownloadedWorkspaceFile(file, bytes);
    }

    @Transactional
    public WorkspaceDto.FileDto createTextFileForBot(Long workspaceId, Long botId, Long folderId,
            String fileName, String content, String versionNote) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        BotConfig bot = requireBotWithOwner(botId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireBotWritable(workspace, folder, null, botId);
        String safeFileName = generatedFileName(fileName);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文本内容过大");
        }
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(safeFileName, bytes);
        assertWithinQuota(workspace, bytes.length);
        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                safeFileName, "text/plain; charset=utf-8", bytes);

        User owner = bot.getCreatedBy();
        WorkspaceFile file = new WorkspaceFile();
        file.setWorkspace(workspace);
        file.setFolder(folder);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCreatedBy(owner);
        file.setSourceBot(bot);
        file.setSourceType(WorkspaceFile.SourceType.BOT);
        file.setBotAccessEnabled(true); // a bot-authored file stays bot-readable/editable
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile savedFile = workspaceFileRepository.save(file);
        saveVersion(savedFile, 1, storedFile, bytes, owner, bot, versionNote, scan);
        adjustWorkspaceUsage(workspace, storedFile.size());
        auditLogService.record(owner, "WORKSPACE_FILE_BOT_CREATE", "WORKSPACE_FILE",
                savedFile.getId(), null, savedFile.getDisplayName() + " (bot " + bot.getId() + ")");
        return WorkspaceDto.FileDto.fromEntity(savedFile);
    }

    @Transactional
    public WorkspaceDto.FileDto uploadFileForBot(Long workspaceId, Long botId, Long folderId,
            String versionNote, MultipartFile multipartFile) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        BotConfig bot = requireBotWithOwner(botId);
        WorkspaceFolder folder = folderId == null ? null : loadFolder(workspaceId, folderId);
        requireBotWritable(workspace, folder, null, botId);

        byte[] bytes = multipartFile.getBytes();
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(multipartFile, bytes);
        assertWithinQuota(workspace, bytes.length);
        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(multipartFile);

        User owner = bot.getCreatedBy();
        WorkspaceFile file = new WorkspaceFile();
        file.setWorkspace(workspace);
        file.setFolder(folder);
        file.setDisplayName(storedFile.originalFileName());
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setMimeType(storedFile.contentType());
        file.setFileSize(storedFile.size());
        file.setCreatedBy(owner);
        file.setSourceBot(bot);
        file.setSourceType(WorkspaceFile.SourceType.BOT);
        file.setBotAccessEnabled(true);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile savedFile = workspaceFileRepository.save(file);
        saveVersion(savedFile, 1, storedFile, bytes, owner, bot, versionNote, scan);
        adjustWorkspaceUsage(workspace, storedFile.size());
        auditLogService.record(owner, "WORKSPACE_FILE_BOT_UPLOAD", "WORKSPACE_FILE",
                savedFile.getId(), null, savedFile.getDisplayName() + " (bot " + bot.getId() + ")");
        return WorkspaceDto.FileDto.fromEntity(savedFile);
    }

    @Transactional
    public WorkspaceDto.FileDto saveTextVersionForBot(Long workspaceId, Long fileId, Long botId,
            String content, String versionNote) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        BotConfig bot = requireBotWithOwner(botId);
        requireBotWritable(workspace, file.getFolder(), file, botId);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EDITABLE_TEXT_BYTES) {
            throw new IllegalArgumentException("文本内容过大");
        }
        String fileName = file.getDisplayName() == null || file.getDisplayName().isBlank()
                ? "edited.txt" : file.getDisplayName();
        WorkspaceFileScanService.ScanResult scan = workspaceFileScanService.scan(fileName, bytes);
        long delta = bytes.length - safeSize(file.getFileSize());
        assertWithinQuota(workspace, delta);
        FileStorageService.StoredFile storedFile = fileStorageService.uploadWorkspaceFile(
                fileName,
                file.getMimeType() == null || file.getMimeType().isBlank()
                        ? "text/plain; charset=utf-8" : file.getMimeType(),
                bytes);
        int nextVersion = Optional.ofNullable(file.getCurrentVersion()).orElse(0) + 1;
        User owner = bot.getCreatedBy();
        saveVersion(file, nextVersion, storedFile, bytes, owner, bot, versionNote, scan);
        file.setCurrentStorageName(storedFile.storageFileName());
        file.setFileSize(storedFile.size());
        file.setCurrentVersion(nextVersion);
        file.setScanStatus(scan.status());
        file.setScanSummary(scan.summary());
        file.setScannedAt(scan.scannedAt());
        file.setStorageProvider(storedFile.storageProvider());
        file.setObjectKey(storedFile.objectKey());
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, delta);
        auditLogService.record(owner, "WORKSPACE_FILE_BOT_EDIT", "WORKSPACE_FILE",
                saved.getId(), null, saved.getDisplayName() + "#" + nextVersion + " (bot " + bot.getId() + ")");
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto.VersionDto> listVersions(Long workspaceId, Long fileId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireUserResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                actorId, WorkspacePermission.AccessLevel.VIEW);
        return workspaceFileVersionRepository.findByFileIdOrderByVersionNumberDesc(fileId)
                .stream()
                .map(WorkspaceDto.VersionDto::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.DownloadedWorkspaceFile downloadFile(Long workspaceId, Long fileId, Long actorId) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireUserResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                actorId, WorkspacePermission.AccessLevel.VIEW);
        byte[] bytes = fileStorageService.getWorkspaceFile(
                file.getStorageProvider(),
                storageKey(file));
        return new WorkspaceDto.DownloadedWorkspaceFile(file, bytes);
    }

    @Transactional
    public WorkspaceDto.FileDto setFileLock(
            Long workspaceId,
            Long fileId,
            Long actorId,
            WorkspaceDto.LockRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        boolean locked = request == null || !Boolean.FALSE.equals(request.getLocked());
        file.setIsLocked(locked);
        file.setLockReason(locked && request != null ? request.getReason() : null);
        file.setLockedBy(locked ? actor : null);
        WorkspaceFile saved = workspaceFileRepository.save(file);
        auditLogService.record(actor, locked ? "WORKSPACE_FILE_LOCK" : "WORKSPACE_FILE_UNLOCK",
                "WORKSPACE_FILE", fileId, null, file.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional
    public WorkspaceDto.FileDto softDeleteFile(Long workspaceId, Long fileId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        User actor = getUser(actorId);
        requireWritable(workspace, file.getFolder(), file, actorId);
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        file.setDeletedBy(actor);
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, -safeSize(file.getFileSize()));
        auditLogService.record(actor, "WORKSPACE_FILE_DELETE", "WORKSPACE_FILE",
                fileId, null, file.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional
    public WorkspaceDto.FileDto restoreFile(Long workspaceId, Long fileId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadAnyFile(workspaceId, fileId);
        requireUserResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                actorId, WorkspacePermission.AccessLevel.EDIT);
        if (file.getFolder() != null && Boolean.TRUE.equals(file.getFolder().getIsDeleted())) {
            throw new IllegalArgumentException("请先恢复父文件夹");
        }
        if (!Boolean.TRUE.equals(file.getIsDeleted())) {
            return WorkspaceDto.FileDto.fromEntity(file);
        }
        assertWithinQuota(workspace, safeSize(file.getFileSize()));
        file.setIsDeleted(false);
        file.setDeletedAt(null);
        file.setDeletedBy(null);
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, safeSize(file.getFileSize()));
        auditLogService.record(getUser(actorId), "WORKSPACE_FILE_RESTORE", "WORKSPACE_FILE",
                fileId, null, file.getDisplayName());
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional
    public WorkspaceDto.FolderDto softDeleteFolder(Long workspaceId, Long folderId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFolder folder = loadFolder(workspaceId, folderId);
        User actor = getUser(actorId);
        requireWritable(workspace, folder, null, actorId);
        long removedBytes = softDeleteFolderRecursive(workspace, folder, actor);
        adjustWorkspaceUsage(workspace, -removedBytes);
        auditLogService.record(actor, "WORKSPACE_FOLDER_DELETE", "WORKSPACE_FOLDER",
                folderId, null, folder.getName());
        return WorkspaceDto.FolderDto.fromEntity(folder);
    }

    @Transactional
    public WorkspaceDto.FolderDto restoreFolder(Long workspaceId, Long folderId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFolder folder = loadAnyFolder(workspaceId, folderId);
        User actor = getUser(actorId);
        requireUserResourceAccess(workspace, folderId, WorkspacePermission.ResourceType.FOLDER,
                actorId, WorkspacePermission.AccessLevel.EDIT);
        if (folder.getParentFolder() != null && Boolean.TRUE.equals(folder.getParentFolder().getIsDeleted())) {
            throw new IllegalArgumentException("请先恢复父文件夹");
        }
        long restoredBytes = bytesInDeletedFolder(folder);
        assertWithinQuota(workspace, restoredBytes);
        restoreFolderRecursive(folder);
        adjustWorkspaceUsage(workspace, restoredBytes);
        auditLogService.record(actor, "WORKSPACE_FOLDER_RESTORE", "WORKSPACE_FOLDER",
                folderId, null, folder.getName());
        return WorkspaceDto.FolderDto.fromEntity(folder);
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.TrashResponse listTrash(Long workspaceId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.VIEW);
        return new WorkspaceDto.TrashResponse(
                workspaceFolderRepository.findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(workspaceId)
                        .stream()
                        .map(WorkspaceDto.FolderDto::fromEntity)
                        .toList(),
                workspaceFileRepository.findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(workspaceId)
                        .stream()
                        .map(WorkspaceDto.FileDto::fromEntity)
                        .toList());
    }

    @Transactional
    public WorkspaceDto.FileDto restoreVersion(
            Long workspaceId,
            Long fileId,
            Integer versionNumber,
            Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        User actor = getUser(actorId);
        requireWritable(workspace, file.getFolder(), file, actorId);
        WorkspaceFileVersion version = workspaceFileVersionRepository
                .findByFileIdAndVersionNumber(fileId, versionNumber)
                .orElseThrow(() -> new IllegalArgumentException("文件版本不存在"));
        long delta = safeSize(version.getFileSize()) - safeSize(file.getFileSize());
        assertWithinQuota(workspace, delta);
        int nextVersion = Optional.ofNullable(file.getCurrentVersion()).orElse(0) + 1;
        saveVersionFromExisting(file, nextVersion, version, actor,
                "恢复到 v" + version.getVersionNumber());
        file.setDisplayName(version.getOriginalName());
        file.setCurrentStorageName(version.getStorageName());
        file.setMimeType(version.getMimeType());
        file.setFileSize(version.getFileSize());
        file.setCurrentVersion(nextVersion);
        file.setScanStatus(version.getScanStatus());
        file.setScanSummary(version.getScanSummary());
        file.setScannedAt(version.getScannedAt());
        file.setStorageProvider(version.getStorageProvider());
        file.setObjectKey(version.getObjectKey());
        WorkspaceFile saved = workspaceFileRepository.save(file);
        adjustWorkspaceUsage(workspace, delta);
        auditLogService.record(actor, "WORKSPACE_FILE_VERSION_RESTORE", "WORKSPACE_FILE",
                fileId, null, "v" + versionNumber + "->v" + nextVersion);
        return WorkspaceDto.FileDto.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.DownloadedWorkspaceFile previewFile(Long workspaceId, Long fileId, Long actorId) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceFile file = loadFile(workspaceId, fileId);
        requireUserResourceAccess(workspace, fileId, WorkspacePermission.ResourceType.FILE,
                actorId, WorkspacePermission.AccessLevel.VIEW);
        if (!isPreviewable(file)) {
            throw new IllegalArgumentException("该文件类型暂不支持预览");
        }
        byte[] bytes = fileStorageService.getWorkspaceFile(
                file.getStorageProvider(),
                storageKey(file));
        return new WorkspaceDto.DownloadedWorkspaceFile(file, bytes);
    }

    @Transactional
    public WorkspaceDto.MaintenanceResult cleanupOrphanWorkspaceFiles(Long workspaceId, Long actorId, boolean dryRun) throws IOException {
        Workspace workspace = loadWorkspace(workspaceId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        Set<String> referenced = new HashSet<>(workspaceFileRepository.findAllCurrentStorageNames());
        referenced.addAll(workspaceFileVersionRepository.findAllStorageNames());
        List<String> orphans = fileStorageService.listWorkspaceFileNames()
                .stream()
                .filter(name -> !referenced.contains(name))
                .sorted()
                .toList();
        long bytes = 0L;
        for (String name : orphans) {
            bytes += fileStorageService.getWorkspaceFile(null, name).length;
        }
        int deleted = 0;
        if (!dryRun) {
            for (String name : orphans) {
                if (fileStorageService.deleteWorkspaceFile(name)) {
                    deleted++;
                }
            }
            auditLogService.record(getUser(actorId), "WORKSPACE_ORPHAN_CLEANUP", "WORKSPACE",
                    workspaceId, null, "deleted=" + deleted);
        }
        return new WorkspaceDto.MaintenanceResult(
                orphans.size(),
                deleted,
                bytes,
                dryRun,
                orphans);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto.PermissionDto> listPermissions(Long workspaceId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        return workspacePermissionRepository.findByWorkspaceIdOrderByResourceTypeAscResourceIdAscPrincipalTypeAscPrincipalIdAsc(workspaceId)
                .stream()
                .map(this::permissionDto)
                .toList();
    }

    @Transactional
    public WorkspaceDto.PermissionDto grantPermission(
            Long workspaceId,
            Long actorId,
            WorkspaceDto.GrantPermissionRequest request) {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        validateResource(workspaceId, request.getResourceType(), request.getResourceId());
        if (request.getPrincipalType() == WorkspacePermission.PrincipalType.USER) {
            getUser(request.getPrincipalId());
        } else {
            botConfigRepository.findById(request.getPrincipalId())
                    .orElseThrow(() -> new IllegalArgumentException("Bot 不存在"));
        }

        WorkspacePermission permission = workspacePermissionRepository
                .findByWorkspaceIdAndResourceTypeAndResourceIdAndPrincipalTypeAndPrincipalId(
                        workspaceId,
                        request.getResourceType(),
                        normalizedResourceId(request.getResourceType(), request.getResourceId()),
                        request.getPrincipalType(),
                        request.getPrincipalId())
                .orElseGet(WorkspacePermission::new);
        permission.setWorkspace(workspace);
        permission.setResourceType(request.getResourceType());
        permission.setResourceId(normalizedResourceId(request.getResourceType(), request.getResourceId()));
        permission.setPrincipalType(request.getPrincipalType());
        permission.setPrincipalId(request.getPrincipalId());
        permission.setAccessLevel(request.getAccessLevel());
        permission.setCreatedBy(actor);
        WorkspacePermission saved = workspacePermissionRepository.save(permission);
        auditLogService.record(actor, "WORKSPACE_PERMISSION_GRANT", "WORKSPACE",
                workspaceId, null, request.getPrincipalType() + ":" + request.getPrincipalId() + ":" + request.getAccessLevel());
        return permissionDto(saved);
    }

    @Transactional
    public void revokePermission(Long workspaceId, Long permissionId, Long actorId) {
        Workspace workspace = loadWorkspace(workspaceId);
        User actor = getUser(actorId);
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.MANAGE);
        WorkspacePermission permission = workspacePermissionRepository.findByIdAndWorkspaceId(permissionId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("权限记录不存在"));
        workspacePermissionRepository.delete(permission);
        auditLogService.record(actor, "WORKSPACE_PERMISSION_REVOKE", "WORKSPACE",
                workspaceId, null, permission.getPrincipalType() + ":" + permission.getPrincipalId());
    }

    private WorkspaceDto.PermissionDto permissionDto(WorkspacePermission permission) {
        return WorkspaceDto.PermissionDto.fromEntity(
                permission,
                resolvePermissionResourceName(permission),
                resolvePermissionPrincipalName(permission));
    }

    private String resolvePermissionResourceName(WorkspacePermission permission) {
        if (permission.getResourceType() == WorkspacePermission.ResourceType.WORKSPACE) {
            return permission.getWorkspace() != null ? permission.getWorkspace().getName() : "工作区";
        }
        if (permission.getResourceType() == WorkspacePermission.ResourceType.FOLDER) {
            return workspaceFolderRepository.findById(permission.getResourceId())
                    .map(WorkspaceFolder::getName)
                    .orElse("文件夹 #" + permission.getResourceId());
        }
        if (permission.getResourceType() == WorkspacePermission.ResourceType.FILE) {
            return workspaceFileRepository.findById(permission.getResourceId())
                    .map(WorkspaceFile::getDisplayName)
                    .orElse("文件 #" + permission.getResourceId());
        }
        return "资源";
    }

    private String resolvePermissionPrincipalName(WorkspacePermission permission) {
        if (permission.getPrincipalType() == WorkspacePermission.PrincipalType.USER) {
            return userRepository.findById(permission.getPrincipalId())
                    .map(user -> user.getDisplayName() != null && !user.getDisplayName().isBlank()
                            ? user.getDisplayName()
                            : user.getUsername())
                    .orElse("用户 #" + permission.getPrincipalId());
        }
        return botConfigRepository.findById(permission.getPrincipalId())
                .map(BotConfig::getBotName)
                .orElse("Bot #" + permission.getPrincipalId());
    }

    public WorkspacePermission.AccessLevel effectiveUserAccess(Workspace workspace, Long userId) {
        WorkspacePermission.AccessLevel level = WorkspacePermission.AccessLevel.NONE;
        if (workspace.getOwner() != null && Objects.equals(workspace.getOwner().getId(), userId)) {
            level = max(level, WorkspacePermission.AccessLevel.MANAGE);
        }
        level = max(level, workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .map(member -> accessForRole(member.getRole()))
                .orElse(WorkspacePermission.AccessLevel.NONE));
        level = max(level, explicitAccess(workspace.getId(), WorkspacePermission.ResourceType.WORKSPACE,
                null, WorkspacePermission.PrincipalType.USER, userId));
        return level;
    }

    private void saveVersion(
            WorkspaceFile file,
            int versionNumber,
            FileStorageService.StoredFile storedFile,
            byte[] bytes,
            User uploadedBy,
            BotConfig uploadedByBot,
            String versionNote,
            WorkspaceFileScanService.ScanResult scan) {
        WorkspaceFileVersion version = new WorkspaceFileVersion();
        version.setFile(file);
        version.setVersionNumber(versionNumber);
        version.setStorageName(storedFile.storageFileName());
        version.setOriginalName(storedFile.originalFileName());
        version.setMimeType(storedFile.contentType());
        version.setFileSize(storedFile.size());
        version.setChecksumSha256(sha256(bytes));
        version.setScanStatus(scan.status());
        version.setScanSummary(scan.summary());
        version.setScannedAt(scan.scannedAt());
        version.setStorageProvider(storedFile.storageProvider());
        version.setObjectKey(storedFile.objectKey());
        version.setUploadedBy(uploadedBy);
        version.setUploadedByBot(uploadedByBot);
        version.setVersionNote(versionNote);
        workspaceFileVersionRepository.save(version);
    }

    private void saveVersionFromExisting(
            WorkspaceFile file,
            int versionNumber,
            WorkspaceFileVersion source,
            User uploadedBy,
            String versionNote) {
        WorkspaceFileVersion version = new WorkspaceFileVersion();
        version.setFile(file);
        version.setVersionNumber(versionNumber);
        version.setStorageName(source.getStorageName());
        version.setOriginalName(source.getOriginalName());
        version.setMimeType(source.getMimeType());
        version.setFileSize(source.getFileSize());
        version.setChecksumSha256(source.getChecksumSha256());
        version.setScanStatus(source.getScanStatus());
        version.setScanSummary(source.getScanSummary());
        version.setScannedAt(source.getScannedAt());
        version.setStorageProvider(source.getStorageProvider());
        version.setObjectKey(source.getObjectKey());
        version.setUploadedBy(uploadedBy);
        version.setUploadedByBot(null);
        version.setVersionNote(versionNote);
        workspaceFileVersionRepository.save(version);
    }

    private String generatedFileName(String fileName) {
        String fallback = "agent-result-" + System.currentTimeMillis() + ".txt";
        String cleaned = StringUtils.cleanPath(fileName == null || fileName.isBlank() ? fallback : fileName);
        return cleaned.contains(".") ? cleaned : cleaned + ".txt";
    }

    private String safeOriginalName(MultipartFile multipartFile) {
        String originalName = multipartFile.getOriginalFilename();
        return StringUtils.cleanPath(originalName == null || originalName.isBlank() ? "file.bin" : originalName);
    }

    private boolean isPreviewable(WorkspaceFile file) {
        String mimeType = file.getMimeType() == null ? "" : file.getMimeType().toLowerCase(Locale.ROOT);
        String name = file.getDisplayName() == null ? "" : file.getDisplayName().toLowerCase(Locale.ROOT);
        return mimeType.startsWith("image/")
                || mimeType.startsWith("text/")
                || mimeType.equals("application/pdf")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".json")
                || name.endsWith(".csv")
                || name.endsWith(".log");
    }

    private String storageKey(WorkspaceFile file) {
        return file.getObjectKey() != null && !file.getObjectKey().isBlank()
                ? file.getObjectKey()
                : file.getCurrentStorageName();
    }

    private void assertWithinQuota(Workspace workspace, long deltaBytes) {
        if (deltaBytes <= 0) {
            return;
        }
        long quota = workspace.getQuotaBytes() != null && workspace.getQuotaBytes() > 0
                ? workspace.getQuotaBytes()
                : DEFAULT_WORKSPACE_QUOTA_BYTES;
        long used = workspace.getUsedBytes() != null ? workspace.getUsedBytes() : 0L;
        if (used + deltaBytes > quota) {
            throw new IllegalArgumentException("资料库配额不足");
        }
    }

    private void adjustWorkspaceUsage(Workspace workspace, long deltaBytes) {
        long used = workspace.getUsedBytes() != null ? workspace.getUsedBytes() : 0L;
        workspace.setUsedBytes(Math.max(0L, used + deltaBytes));
        if (workspace.getQuotaBytes() == null || workspace.getQuotaBytes() <= 0) {
            workspace.setQuotaBytes(DEFAULT_WORKSPACE_QUOTA_BYTES);
        }
        workspaceRepository.save(workspace);
    }

    private long safeSize(Long size) {
        return size == null ? 0L : size;
    }

    private BotConfig resolveSourceBot(Long sourceBotId, Long actorId, Workspace workspace) {
        if (sourceBotId == null) {
            return null;
        }
        BotConfig bot = botConfigRepository.findById(sourceBotId)
                .orElseThrow(() -> new IllegalArgumentException("Bot 不存在"));
        boolean ownedByActor = bot.getCreatedBy() != null && Objects.equals(bot.getCreatedBy().getId(), actorId);
        boolean canManageWorkspace = effectiveUserAccess(workspace, actorId).allows(WorkspacePermission.AccessLevel.MANAGE);
        if (!ownedByActor && !canManageWorkspace) {
            throw new AccessDeniedException("没有权限代表该 Bot 提交文件");
        }
        if (!Boolean.TRUE.equals(workspace.getBotAccessEnabled())) {
            throw new AccessDeniedException("该工作区未允许 Bot 文件写入");
        }
        return bot;
    }

    private void requireWritable(Workspace workspace, WorkspaceFolder folder, WorkspaceFile file, Long actorId) {
        requireUserAccess(workspace, actorId, WorkspacePermission.AccessLevel.EDIT);
        if (Boolean.TRUE.equals(workspace.getIsLocked()) && !effectiveUserAccess(workspace, actorId).allows(WorkspacePermission.AccessLevel.MANAGE)) {
            throw new AccessDeniedException("工作区已锁定，仅管理员可写入");
        }
        if (folder != null && Boolean.TRUE.equals(folder.getIsLocked())
                && !canBypassLock(folder.getLockedBy(), actorId, workspace)) {
            throw new AccessDeniedException("文件夹已锁定");
        }
        if (file != null && Boolean.TRUE.equals(file.getIsLocked())
                && !canBypassLock(file.getLockedBy(), actorId, workspace)) {
            throw new AccessDeniedException("文件已锁定");
        }
    }

    private boolean canBypassLock(User lockedBy, Long actorId, Workspace workspace) {
        return (lockedBy != null && Objects.equals(lockedBy.getId(), actorId))
                || effectiveUserAccess(workspace, actorId).allows(WorkspacePermission.AccessLevel.MANAGE);
    }

    private void requireUserAccess(Workspace workspace, Long userId, WorkspacePermission.AccessLevel required) {
        if (!effectiveUserAccess(workspace, userId).allows(required)) {
            throw new AccessDeniedException("没有工作区权限");
        }
    }

    private void requireWorkspaceVisible(Workspace workspace, Long userId) {
        if (!hasAnyUserAccess(workspace, userId)) {
            throw new AccessDeniedException("没有工作区权限");
        }
    }

    private boolean hasAnyUserAccess(Workspace workspace, Long userId) {
        return effectiveUserAccess(workspace, userId).allows(WorkspacePermission.AccessLevel.VIEW)
                || workspacePermissionRepository.findByWorkspaceIdAndPrincipalTypeAndPrincipalId(
                        workspace.getId(),
                        WorkspacePermission.PrincipalType.USER,
                        userId)
                .stream()
                .anyMatch(permission -> permission.getAccessLevel().allows(WorkspacePermission.AccessLevel.VIEW));
    }

    private void requireUserResourceAccess(
            Workspace workspace,
            Long resourceId,
            WorkspacePermission.ResourceType resourceType,
            Long userId,
            WorkspacePermission.AccessLevel required) {
        if (!hasUserResourceAccess(workspace, resourceId, resourceType, userId, required)) {
            throw new AccessDeniedException("没有资源权限");
        }
    }

    private boolean hasUserResourceAccess(
            Workspace workspace,
            Long resourceId,
            WorkspacePermission.ResourceType resourceType,
            Long userId,
            WorkspacePermission.AccessLevel required) {
        WorkspacePermission.AccessLevel level = effectiveUserAccess(workspace, userId);
        level = max(level, explicitAccess(workspace.getId(), resourceType, resourceId,
                WorkspacePermission.PrincipalType.USER, userId));
        return level.allows(required);
    }

    private WorkspacePermission.AccessLevel explicitAccess(
            Long workspaceId,
            WorkspacePermission.ResourceType resourceType,
            Long resourceId,
            WorkspacePermission.PrincipalType principalType,
            Long principalId) {
        return workspacePermissionRepository
                .findByWorkspaceIdAndResourceTypeAndResourceIdAndPrincipalTypeAndPrincipalId(
                        workspaceId,
                        resourceType,
                        normalizedResourceId(resourceType, resourceId),
                        principalType,
                        principalId)
                .map(WorkspacePermission::getAccessLevel)
                .orElse(WorkspacePermission.AccessLevel.NONE);
    }

    private WorkspacePermission.AccessLevel accessForRole(WorkspaceMember.WorkspaceRole role) {
        if (role == null) {
            return WorkspacePermission.AccessLevel.NONE;
        }
        return switch (role) {
            case OWNER, ADMIN -> WorkspacePermission.AccessLevel.MANAGE;
            case EDITOR, SERVICE -> WorkspacePermission.AccessLevel.EDIT;
            case VIEWER -> WorkspacePermission.AccessLevel.VIEW;
        };
    }

    private WorkspacePermission.AccessLevel max(
            WorkspacePermission.AccessLevel left,
            WorkspacePermission.AccessLevel right) {
        return left.getRank() >= right.getRank() ? left : right;
    }

    private Workspace loadWorkspace(Long workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("工作区不存在"));
        if (!Boolean.TRUE.equals(workspace.getIsActive())) {
            throw new IllegalArgumentException("工作区已停用");
        }
        return workspace;
    }

    private long softDeleteFolderRecursive(Workspace workspace, WorkspaceFolder folder, User actor) {
        long removedBytes = 0L;
        List<WorkspaceFile> files = workspaceFileRepository
                .findByWorkspaceIdAndFolderIdAndIsDeletedFalseOrderByUpdatedAtDesc(workspace.getId(), folder.getId());
        for (WorkspaceFile file : files) {
            file.setIsDeleted(true);
            file.setDeletedAt(LocalDateTime.now());
            file.setDeletedBy(actor);
            removedBytes += safeSize(file.getFileSize());
        }
        workspaceFileRepository.saveAll(files);

        List<WorkspaceFolder> childFolders = workspaceFolderRepository
                .findByWorkspaceIdAndParentFolderIdAndIsDeletedFalseOrderByNameAsc(workspace.getId(), folder.getId());
        for (WorkspaceFolder child : childFolders) {
            removedBytes += softDeleteFolderRecursive(workspace, child, actor);
        }
        folder.setIsDeleted(true);
        folder.setDeletedAt(LocalDateTime.now());
        folder.setDeletedBy(actor);
        workspaceFolderRepository.save(folder);
        return removedBytes;
    }

    private long bytesInDeletedFolder(WorkspaceFolder folder) {
        long bytes = workspaceFileRepository.findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(folder.getWorkspace().getId())
                .stream()
                .filter(file -> isInsideFolder(file.getFolder(), folder))
                .mapToLong(file -> safeSize(file.getFileSize()))
                .sum();
        return bytes;
    }

    private void restoreFolderRecursive(WorkspaceFolder folder) {
        List<WorkspaceFile> files = workspaceFileRepository.findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(folder.getWorkspace().getId())
                .stream()
                .filter(file -> file.getFolder() != null && Objects.equals(file.getFolder().getId(), folder.getId()))
                .toList();
        for (WorkspaceFile file : files) {
            file.setIsDeleted(false);
            file.setDeletedAt(null);
            file.setDeletedBy(null);
        }
        workspaceFileRepository.saveAll(files);

        List<WorkspaceFolder> childFolders = workspaceFolderRepository.findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(folder.getWorkspace().getId())
                .stream()
                .filter(candidate -> candidate.getParentFolder() != null
                        && Objects.equals(candidate.getParentFolder().getId(), folder.getId()))
                .toList();
        for (WorkspaceFolder child : childFolders) {
            restoreFolderRecursive(child);
        }
        folder.setIsDeleted(false);
        folder.setDeletedAt(null);
        folder.setDeletedBy(null);
        workspaceFolderRepository.save(folder);
    }

    private boolean isInsideFolder(WorkspaceFolder candidate, WorkspaceFolder ancestor) {
        WorkspaceFolder current = candidate;
        while (current != null) {
            if (Objects.equals(current.getId(), ancestor.getId())) {
                return true;
            }
            current = current.getParentFolder();
        }
        return false;
    }

    private WorkspaceFolder loadFolder(Long workspaceId, Long folderId) {
        return workspaceFolderRepository.findByIdAndWorkspaceIdAndIsDeletedFalse(folderId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("文件夹不存在"));
    }

    private WorkspaceFolder loadAnyFolder(Long workspaceId, Long folderId) {
        return workspaceFolderRepository.findByIdAndWorkspaceId(folderId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("文件夹不存在"));
    }

    private WorkspaceFile loadFile(Long workspaceId, Long fileId) {
        return workspaceFileRepository.findByIdAndWorkspaceIdAndIsDeletedFalse(fileId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
    }

    private WorkspaceFile loadAnyFile(Long workspaceId, Long fileId) {
        return workspaceFileRepository.findByIdAndWorkspaceId(fileId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void validateResource(
            Long workspaceId,
            WorkspacePermission.ResourceType resourceType,
            Long resourceId) {
        if (resourceType == WorkspacePermission.ResourceType.WORKSPACE) {
            return;
        }
        if (resourceId == null) {
            throw new IllegalArgumentException("资源 ID 不能为空");
        }
        if (resourceType == WorkspacePermission.ResourceType.FOLDER) {
            loadFolder(workspaceId, resourceId);
        } else if (resourceType == WorkspacePermission.ResourceType.FILE) {
            loadFile(workspaceId, resourceId);
        }
    }

    private Long normalizedResourceId(WorkspacePermission.ResourceType resourceType, Long resourceId) {
        return resourceType == WorkspacePermission.ResourceType.WORKSPACE ? null : resourceId;
    }

    private String cleanName(String raw, String message) {
        String value = StringUtils.trimWhitespace(raw);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
