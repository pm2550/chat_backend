package com.chatapp.service;

import com.chatapp.dto.WorkspaceDto;
import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.entity.WorkspacePermission;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.tool.InspectRoomImageTool;
import com.chatapp.service.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Inbound bot gateway (Phase 4 / F1): an external service authenticated by a bot
 * token posts a message AS the bot into a room it is bound to. Reuses the normal
 * Message + WebSocket fan-out path so the post renders exactly like an in-app bot reply.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotGatewayService {

    private static final int MAX_CONTENT_LENGTH = 8000;

    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final FileStorageService fileStorageService;
    private final InspectRoomImageTool inspectRoomImageTool;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final ChatRoomService chatRoomService;
    private final BotService botService;
    private final ModerationService moderationService;
    private final FriendshipService friendshipService;

    /** Room ids this bot is actively bound to (where it may post). */
    @Transactional(readOnly = true)
    public List<Long> boundRoomIds(BotConfig bot) {
        return chatRoomBotRepository.findByBotConfigIdAndIsActiveTrue(bot.getId()).stream()
                .map(crb -> crb.getChatRoom().getId())
                .toList();
    }

    /**
     * Post a plain-text message as {@code bot} into {@code chatRoomId}. The bot must be
     * bound to the room. Author is the bot's owner (or room creator) so existing
     * sender-not-null invariants hold; the bot identity rides on message.botConfig.
     */
    @Transactional
    public Message postAsBot(BotConfig bot, Long chatRoomId, String rawContent) {
        return postAsBot(bot, chatRoomId, rawContent, null, null);
    }

    @Transactional
    public Message postAsBot(BotConfig bot, Long chatRoomId, String rawContent, Long replyToMessageId, Long fileId) {
        if (fileId != null) {
            return postExistingFileAsBot(bot, chatRoomId, rawContent, replyToMessageId, fileId);
        }
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        ChatRoomBot binding = requireActiveBinding(bot, chatRoomId);
        ChatRoom room = requireRoom(chatRoomId);
        User sender = requireBotSender(bot, room);
        Message replyTo = resolveReplyTo(chatRoomId, replyToMessageId);

        String content = truncate(rawContent);

        Message message = new Message();
        message.setChatRoom(room);
        message.setSender(sender);
        message.setBotConfig(bot);
        message.setBotDisplayName(displayName(bot, binding));
        message.setReplyToMessage(replyTo);
        message.setMessageType(Message.MessageType.TEXT);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent(content);
        message = messageRepository.save(message);

        broadcastBotMessage(message, chatRoomId, sender.getId());
        log.info("inbound bot post: bot={} room={} messageId={}", bot.getId(), chatRoomId, message.getId());
        return message;
    }

    @Transactional
    public Message postFileAsBot(BotConfig bot,
                                 Long chatRoomId,
                                 MultipartFile file,
                                 Message.MessageType requestedMessageType,
                                 Long replyToMessageId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        ChatRoomBot binding = requireActiveBinding(bot, chatRoomId);
        ChatRoom room = requireRoom(chatRoomId);
        User sender = requireBotSender(bot, room);
        Message replyTo = resolveReplyTo(chatRoomId, replyToMessageId);

        String fileUrl = fileStorageService.uploadChatFile(file);
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "file";
        }
        String contentType = file.getContentType();
        Message.MessageType messageType = requestedMessageType != null
                ? normalizeAttachmentMessageType(requestedMessageType)
                : inferAttachmentMessageType(fileName, contentType);

        Message message = new Message();
        message.setChatRoom(room);
        message.setSender(sender);
        message.setBotConfig(bot);
        message.setBotDisplayName(displayName(bot, binding));
        message.setReplyToMessage(replyTo);
        message.setMessageType(messageType);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent(fileName);
        message.setFileUrl(fileUrl);
        message.setFileName(fileName);
        message.setFileType(contentType);
        message.setFileSize(file.getSize());
        message = messageRepository.save(message);

        broadcastBotMessage(message, chatRoomId, sender.getId());
        log.info("inbound bot file post: bot={} room={} messageId={} file={}",
                bot.getId(), chatRoomId, message.getId(), fileName);
        return message;
    }

    @Transactional(readOnly = true)
    public Page<Message> listMessages(BotConfig bot, Long chatRoomId, Pageable pageable) {
        requireActiveBinding(bot, chatRoomId);
        return messageRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Message> searchMessages(BotConfig bot, Long chatRoomId, String keyword, Pageable pageable) {
        requireActiveBinding(bot, chatRoomId);
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword 不能为空");
        }
        return messageRepository.searchInChatRoom(chatRoomId, keyword.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public GatewayFileDownload downloadFile(BotConfig bot, Long fileId) throws IOException {
        Message message = messageRepository.findWithSenderById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        if (Boolean.TRUE.equals(message.getIsDeleted()) || message.getFileUrl() == null || message.getFileUrl().isBlank()) {
            throw new IllegalArgumentException("文件不存在");
        }
        Long roomId = message.getChatRoom() != null ? message.getChatRoom().getId() : null;
        requireActiveBinding(bot, roomId);
        StoredGatewayFile stored = parseStoredGatewayFile(message.getFileUrl());
        byte[] bytes = fileStorageService.getFile(stored.type(), stored.fileName());
        return new GatewayFileDownload(
                bytes,
                message.getFileName() != null ? message.getFileName() : stored.fileName(),
                message.getFileType() != null ? message.getFileType() : "application/octet-stream",
                roomId,
                message.getId());
    }

    @Transactional(readOnly = true)
    public JsonNode inspectRoomImage(BotConfig bot, Long chatRoomId, Long messageId, Integer latestIndex) {
        requireActiveBinding(bot, chatRoomId);
        ObjectNode params = objectMapper.createObjectNode();
        if (messageId != null) {
            params.put("messageId", messageId);
        }
        if (latestIndex != null) {
            params.put("latestIndex", latestIndex);
        }
        Long userId = bot.getCreatedBy() != null ? bot.getCreatedBy().getId() : null;
        return inspectRoomImageTool.execute(params, new ToolContext(chatRoomId, userId, null, bot.getId()));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto> listWorkspaces(BotConfig bot) {
        return workspaceService.listWorkspacesForBot(bot.getId());
    }

    @Transactional
    public WorkspaceDto createWorkspace(BotConfig bot, WorkspaceDto.CreateWorkspaceRequest request) {
        User owner = requireBotOwner(bot);
        WorkspaceDto.CreateWorkspaceRequest effective = request != null ? request : new WorkspaceDto.CreateWorkspaceRequest();
        effective.setBotAccessEnabled(true);
        WorkspaceDto workspace = workspaceService.createWorkspace(owner.getId(), effective);

        WorkspaceDto.GrantPermissionRequest grant = new WorkspaceDto.GrantPermissionRequest();
        grant.setResourceType(WorkspacePermission.ResourceType.WORKSPACE);
        grant.setResourceId(null);
        grant.setPrincipalType(WorkspacePermission.PrincipalType.BOT);
        grant.setPrincipalId(bot.getId());
        grant.setAccessLevel(WorkspacePermission.AccessLevel.MANAGE);
        workspaceService.grantPermission(workspace.getId(), owner.getId(), grant);

        return workspace;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDto.FileDto> listWorkspaceFiles(BotConfig bot, Long workspaceId, Long folderId) {
        return workspaceService.listFilesForBot(workspaceId, bot.getId(), folderId);
    }

    @Transactional
    public WorkspaceDto.FileDto uploadWorkspaceFile(BotConfig bot,
                                                    Long workspaceId,
                                                    Long folderId,
                                                    String versionNote,
                                                    MultipartFile file) throws IOException {
        return workspaceService.uploadFileForBot(workspaceId, bot.getId(), folderId, versionNote, file);
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.DownloadedWorkspaceFile downloadWorkspaceFile(BotConfig bot,
                                                                      Long workspaceId,
                                                                      Long fileId) throws IOException {
        return workspaceService.downloadFileForBot(workspaceId, fileId, bot.getId());
    }

    @Transactional(readOnly = true)
    public WorkspaceDto.TextContent readWorkspaceText(BotConfig bot, Long workspaceId, Long fileId) throws IOException {
        return workspaceService.readTextForBot(workspaceId, fileId, bot.getId());
    }

    @Transactional
    public WorkspaceDto.FileDto createWorkspaceText(BotConfig bot,
                                                    Long workspaceId,
                                                    WorkspaceDto.CreateTextFileRequest request) throws IOException {
        return workspaceService.createTextFileForBot(
                workspaceId,
                bot.getId(),
                request.getFolderId(),
                request.getFileName(),
                request.getContent(),
                request.getVersionNote());
    }

    @Transactional
    public WorkspaceDto.FileDto saveWorkspaceText(BotConfig bot,
                                                  Long workspaceId,
                                                  Long fileId,
                                                  WorkspaceDto.SaveTextRequest request) throws IOException {
        return workspaceService.saveTextVersionForBot(
                workspaceId,
                fileId,
                bot.getId(),
                request.getContent(),
                request.getVersionNote());
    }

    @Transactional
    public ChatRoom createRoom(BotConfig bot, String name, String description, List<Long> memberIds) {
        User owner = requireBotOwner(bot);
        ChatRoom room = chatRoomService.createGroupChat(owner.getId(), name, description, memberIds);
        bindBotToRoomIfNeeded(bot, room.getId(), owner.getId());
        return room;
    }

    @Transactional
    public void addRoomMember(BotConfig bot, Long roomId, Long userId) {
        requireActiveBinding(bot, roomId);
        User owner = requireBotOwner(bot);
        chatRoomService.addMember(roomId, owner.getId(), userId);
    }

    @Transactional
    public void kickRoomMember(BotConfig bot, Long roomId, Long userId) {
        moderationService.kickByBot(bot.getId(), roomId, userId);
    }

    @Transactional
    public void muteRoomMember(BotConfig bot, Long roomId, Long userId, boolean muted) {
        moderationService.muteByBot(bot.getId(), roomId, userId, muted);
    }

    @Transactional(readOnly = true)
    public List<User> listFriends(BotConfig bot) {
        User owner = requireBotOwner(bot);
        return friendshipService.getFriends(owner.getId());
    }

    @Transactional
    public Message postFriendMessage(BotConfig bot, Long friendUserId, String content, Long replyToMessageId, Long fileId) {
        User owner = requireBotOwner(bot);
        if (!friendshipService.areFriends(owner.getId(), friendUserId)) {
            throw new AccessDeniedException("对方未添加该机器人所属账号为好友");
        }
        ChatRoom room = chatRoomService.createPrivateChat(owner.getId(), friendUserId);
        bindBotToRoomIfNeeded(bot, room.getId(), owner.getId());
        return postAsBot(bot, room.getId(), content, replyToMessageId, fileId);
    }

    private Message postExistingFileAsBot(BotConfig bot,
                                          Long chatRoomId,
                                          String rawContent,
                                          Long replyToMessageId,
                                          Long fileId) {
        ChatRoomBot binding = requireActiveBinding(bot, chatRoomId);
        ChatRoom room = requireRoom(chatRoomId);
        User sender = requireBotSender(bot, room);
        Message replyTo = resolveReplyTo(chatRoomId, replyToMessageId);
        Message source = messageRepository.findWithSenderById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("file_id 不存在"));
        if (Boolean.TRUE.equals(source.getIsDeleted()) || source.getFileUrl() == null || source.getFileUrl().isBlank()) {
            throw new IllegalArgumentException("file_id 不是可用附件");
        }
        Long sourceRoomId = source.getChatRoom() != null ? source.getChatRoom().getId() : null;
        requireActiveBinding(bot, sourceRoomId);

        Message message = new Message();
        message.setChatRoom(room);
        message.setSender(sender);
        message.setBotConfig(bot);
        message.setBotDisplayName(displayName(bot, binding));
        message.setReplyToMessage(replyTo);
        message.setMessageType(source.getMessageType() != null ? source.getMessageType() : Message.MessageType.FILE);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent(rawContent != null && !rawContent.isBlank()
                ? truncate(rawContent)
                : source.getContent());
        message.setFileUrl(source.getFileUrl());
        message.setFileName(source.getFileName());
        message.setFileType(source.getFileType());
        message.setFileSize(source.getFileSize());
        message.setThumbnailUrl(source.getThumbnailUrl());
        message = messageRepository.save(message);

        broadcastBotMessage(message, chatRoomId, sender.getId());
        log.info("inbound bot file reference post: bot={} room={} sourceMessage={} messageId={}",
                bot.getId(), chatRoomId, fileId, message.getId());
        return message;
    }

    private ChatRoomBot requireActiveBinding(BotConfig bot, Long chatRoomId) {
        if (bot == null || bot.getId() == null || chatRoomId == null) {
            throw new AccessDeniedException("机器人未加入该聊天室");
        }
        ChatRoomBot binding = chatRoomBotRepository
                .findByChatRoomIdAndBotConfigId(chatRoomId, bot.getId())
                .orElseThrow(() -> new AccessDeniedException("机器人未加入该聊天室"));
        if (Boolean.FALSE.equals(binding.getIsActive())) {
            throw new AccessDeniedException("机器人在该聊天室已禁用");
        }
        return binding;
    }

    private ChatRoom requireRoom(Long chatRoomId) {
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new IllegalArgumentException("聊天室不存在"));
    }

    private User requireBotSender(BotConfig bot, ChatRoom room) {
        Long senderId = bot.getCreatedBy() != null
                ? bot.getCreatedBy().getId()
                : room.getCreatedBy().getId();
        return userRepository.findById(senderId)
                .orElseThrow(() -> new IllegalStateException("机器人发送者不存在"));
    }

    private User requireBotOwner(BotConfig bot) {
        if (bot == null || bot.getCreatedBy() == null || bot.getCreatedBy().getId() == null) {
            throw new IllegalStateException("机器人缺少归属用户");
        }
        return userRepository.findById(bot.getCreatedBy().getId())
                .orElseThrow(() -> new IllegalStateException("机器人归属用户不存在"));
    }

    private void bindBotToRoomIfNeeded(BotConfig bot, Long roomId, Long operatorId) {
        if (chatRoomBotRepository.findByChatRoomIdAndBotConfigId(roomId, bot.getId()).isPresent()) {
            return;
        }
        BotDto.AddToChatRoomRequest request = new BotDto.AddToChatRoomRequest();
        request.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
        request.setRoomNickname(bot.getBotName());
        request.setEnabledInRoom(true);
        botService.addBotToChatRoom(roomId, bot.getId(), request, operatorId);
    }

    private Message resolveReplyTo(Long chatRoomId, Long replyToMessageId) {
        if (replyToMessageId == null) {
            return null;
        }
        Message replyTo = messageRepository.findWithSenderById(replyToMessageId)
                .orElseThrow(() -> new IllegalArgumentException("回复的消息不存在"));
        Long replyRoomId = replyTo.getChatRoom() != null ? replyTo.getChatRoom().getId() : null;
        if (!chatRoomId.equals(replyRoomId)) {
            throw new IllegalArgumentException("只能回复同一聊天室的消息");
        }
        return replyTo;
    }

    private void broadcastBotMessage(Message message, Long chatRoomId, Long senderId) {
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoomId, senderId);
        rawWebSocketHandler.broadcastMessage(message);
    }

    private String displayName(BotConfig bot, ChatRoomBot binding) {
        return binding.getRoomNickname() != null && !binding.getRoomNickname().isBlank()
                ? binding.getRoomNickname().trim()
                : bot.getBotName();
    }

    private String truncate(String rawContent) {
        return rawContent.length() > MAX_CONTENT_LENGTH
                ? rawContent.substring(0, MAX_CONTENT_LENGTH)
                : rawContent;
    }

    private Message.MessageType inferAttachmentMessageType(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return Message.MessageType.IMAGE;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")) {
            return Message.MessageType.IMAGE;
        }
        return Message.MessageType.FILE;
    }

    private Message.MessageType normalizeAttachmentMessageType(Message.MessageType requestedMessageType) {
        return requestedMessageType == Message.MessageType.IMAGE ? Message.MessageType.IMAGE : Message.MessageType.FILE;
    }

    private StoredGatewayFile parseStoredGatewayFile(String fileUrl) {
        String chatPrefix = "/api/files/chat/";
        String imageGenPrefix = "/api/files/image-gen/";
        if (fileUrl.startsWith(chatPrefix)) {
            return new StoredGatewayFile("chat", fileUrl.substring(chatPrefix.length()));
        }
        if (fileUrl.startsWith(imageGenPrefix)) {
            return new StoredGatewayFile("image-gen", fileUrl.substring(imageGenPrefix.length()));
        }
        throw new IllegalArgumentException("不支持的文件路径");
    }

    private record StoredGatewayFile(String type, String fileName) {}

    public record GatewayFileDownload(byte[] bytes, String fileName, String contentType, Long roomId, Long fileId) {}
}
