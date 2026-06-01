package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.service.AuditLogService;
import com.chatapp.service.BotService;
import com.chatapp.service.FileStorageService;
import com.chatapp.service.MessageService;
import com.chatapp.service.MessageReactionService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息控制器
 */
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
@Slf4j
public class MessageController {

    private final MessageService messageService;
    private final UserService userService;
    private final FileStorageService fileStorageService;
    private final RawWebSocketHandler rawWebSocketHandler;
    private final BotService botService;
    private final AuditLogService auditLogService;
    private final MessageReactionService messageReactionService;

    /**
     * 发送文本消息
     */
    @PostMapping
    public ResponseEntity<?> sendMessage(@RequestBody SendMessageRequest request, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message.MessageType messageType = request.getMessageType() != null
                    ? request.getMessageType()
                    : Message.MessageType.TEXT;
            Message message = messageType == Message.MessageType.STICKER
                    ? messageService.sendStickerMessage(
                        currentUser.getId(),
                        request.getChatRoomId(),
                        request.getStickerId(),
                        Boolean.TRUE.equals(request.getIsAnonymous()))
                    : Boolean.TRUE.equals(request.getIsAnonymous())
                    ? messageService.sendAnonymousEncryptedMessage(
                        currentUser.getId(),
                        request.getChatRoomId(),
                        request.getContent(),
                        request.getEncryptedContent(),
                        request.getEncryptionVersion(),
                        messageType)
                    : messageService.sendEncryptedMessage(
                        currentUser.getId(),
                        request.getChatRoomId(),
                        request.getContent(),
                        request.getEncryptedContent(),
                        request.getEncryptionVersion(),
                        messageType);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "消息发送成功");
            response.put("data", MessageDto.fromEntity(message));

            rawWebSocketHandler.broadcastMessageExcept(message, currentUser.getId());
            auditLogService.record(
                    currentUser,
                    "MESSAGE_SEND",
                    "MESSAGE",
                    message.getId(),
                    request.getChatRoomId(),
                    message.getMessageType().name());
            if (request.getEncryptedContent() == null || request.getEncryptedContent().isBlank()) {
                processBotsAndBroadcast(message, currentUser.getId());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 发送文件消息
     */
    @PostMapping("/file")
    public ResponseEntity<?> sendFileMessage(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "messageType", required = false) Message.MessageType requestedMessageType,
            @RequestParam(value = "encryptedContent", required = false) String encryptedContent,
            @RequestParam(value = "encryptionVersion", required = false) Integer encryptionVersion,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            messageService.validateCanSendMessage(currentUser.getId(), chatRoomId);
            String fileUrl = fileStorageService.uploadChatFile(file);
            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.isBlank()) {
                fileName = "file";
            }
            String contentType = file.getContentType();

            Message.MessageType messageType = requestedMessageType != null
                    ? normalizeAttachmentMessageType(requestedMessageType)
                    : inferAttachmentMessageType(fileName, contentType);
            
            Message message = messageService.sendFileMessage(
                currentUser.getId(),
                chatRoomId,
                fileName,
                fileUrl,
                contentType,
                file.getSize(),
                messageType,
                encryptedContent,
                encryptionVersion
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "文件消息发送成功");
            response.put("data", MessageDto.fromEntity(message));

            rawWebSocketHandler.broadcastMessageExcept(message, currentUser.getId());
            auditLogService.record(
                    currentUser,
                    "FILE_SEND",
                    "MESSAGE",
                    message.getId(),
                    chatRoomId,
                    fileName);
            processBotsAndBroadcast(message, currentUser.getId());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("发送文件消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 回复消息
     */
    @PostMapping("/reply")
    public ResponseEntity<?> replyToMessage(@RequestBody ReplyMessageRequest request, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message message = messageService.replyToMessage(
                currentUser.getId(),
                request.getChatRoomId(),
                request.getReplyToMessageId(),
                request.getContent(),
                Message.MessageType.TEXT
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "回复消息发送成功");
            response.put("data", MessageDto.fromEntity(message));

            rawWebSocketHandler.broadcastMessageExcept(message, currentUser.getId());
            processBotsAndBroadcast(message, currentUser.getId());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("回复消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取聊天室消息（分页）
     */
    @GetMapping("/chat-room/{chatRoomId}")
    public ResponseEntity<?> getChatRoomMessages(
            @PathVariable Long chatRoomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<Message> messages = messageService.getChatRoomMessages(chatRoomId, currentUser.getId(), pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", toMessageDtos(messages.getContent(), currentUser.getId()));
            response.put("currentPage", messages.getNumber());
            response.put("totalPages", messages.getTotalPages());
            response.put("totalElements", messages.getTotalElements());
            response.put("hasNext", messages.hasNext());
            response.put("hasPrevious", messages.hasPrevious());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取最新消息
     */
    @GetMapping("/chat-room/{chatRoomId}/recent")
    public ResponseEntity<?> getRecentMessages(
            @PathVariable Long chatRoomId,
            @RequestParam(defaultValue = "20") int limit,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<Message> messages = messageService.getRecentMessages(chatRoomId, currentUser.getId(), limit);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", toMessageDtos(messages, currentUser.getId()));
            response.put("count", messages.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取最新消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取聊天室文件中心消息（图片/通用文件）。
     */
    @GetMapping("/chat-room/{chatRoomId}/files")
    public ResponseEntity<?> getChatRoomFileMessages(
            @PathVariable Long chatRoomId,
            @RequestParam(required = false) String messageType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message.MessageType type = parseAttachmentType(messageType);

            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<Message> messages = messageService.getChatRoomFileMessages(
                    chatRoomId,
                    currentUser.getId(),
                    type,
                    pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("messages", toMessageDtos(messages.getContent(), currentUser.getId()));
            response.put("currentPage", messages.getNumber());
            response.put("totalPages", messages.getTotalPages());
            response.put("totalElements", messages.getTotalElements());
            response.put("hasNext", messages.hasNext());
            response.put("hasPrevious", messages.hasPrevious());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室文件失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 标记消息为已读
     */
    @PostMapping("/{messageId}/read")
    public ResponseEntity<?> markMessageAsRead(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            messageService.markMessageAsRead(messageId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "消息已标记为已读"));
        } catch (Exception e) {
            log.error("标记消息已读失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{messageId}/read-by")
    public ResponseEntity<?> getMessageReadBy(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            return ResponseEntity.ok(Map.of(
                    "message", "已读详情",
                    "data", messageService.getReadReceipts(messageId, currentUser.getId())
            ));
        } catch (Exception e) {
            log.error("获取已读详情失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 标记聊天室所有消息为已读
     */
    @PostMapping("/chat-room/{chatRoomId}/read-all")
    public ResponseEntity<?> markAllMessagesAsRead(@PathVariable Long chatRoomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message lastMessage = messageService.markAllMessagesAsRead(chatRoomId, currentUser.getId());
            rawWebSocketHandler.broadcastReadReceipt(
                    chatRoomId,
                    currentUser.getId(),
                    lastMessage != null ? lastMessage.getId() : null);
            auditLogService.record(
                    currentUser,
                    "MESSAGE_READ_ALL",
                    "CHAT_ROOM",
                    chatRoomId,
                    chatRoomId,
                    null);
            
            return ResponseEntity.ok(Map.of("message", "所有消息已标记为已读"));
        } catch (Exception e) {
            log.error("标记所有消息已读失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 清空当前用户在聊天室内的可见历史，不删除其他成员的消息记录。
     */
    @DeleteMapping("/chat-room/{chatRoomId}/clear")
    public ResponseEntity<?> clearChatHistory(@PathVariable Long chatRoomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            messageService.clearChatHistoryForUser(chatRoomId, currentUser.getId());
            auditLogService.record(
                    currentUser,
                    "CHAT_HISTORY_CLEAR",
                    "CHAT_ROOM",
                    chatRoomId,
                    chatRoomId,
                    null);
            return ResponseEntity.ok(Map.of("message", "聊天记录已清空"));
        } catch (Exception e) {
            log.error("清空聊天记录失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 撤回消息
     */
    @PostMapping("/{messageId}/recall")
    public ResponseEntity<?> recallMessage(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message message = messageService.recallMessage(messageId, currentUser.getId());

            rawWebSocketHandler.broadcastMessageExcept(message, currentUser.getId());
            auditLogService.record(
                    currentUser,
                    "MESSAGE_RECALL",
                    "MESSAGE",
                    messageId,
                    message.getChatRoom().getId(),
                    null);

            return ResponseEntity.ok(Map.of(
                "message", "消息已撤回",
                "data", MessageDto.fromEntity(message)
            ));
        } catch (Exception e) {
            log.error("撤回消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除消息
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Message message = messageService.deleteMessage(messageId, currentUser.getId());

            rawWebSocketHandler.broadcastMessageExcept(message, currentUser.getId());
            auditLogService.record(
                    currentUser,
                    "MESSAGE_DELETE",
                    "MESSAGE",
                    messageId,
                    message.getChatRoom().getId(),
                    null);

            return ResponseEntity.ok(Map.of(
                "message", "消息已删除",
                "data", MessageDto.fromEntity(message)
            ));
        } catch (Exception e) {
            log.error("删除消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 搜索消息
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchMessages(
            @RequestParam Long chatRoomId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<Message> messages = messageService.searchMessages(chatRoomId, currentUser.getId(), keyword, pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", toMessageDtos(messages.getContent(), currentUser.getId()));
            response.put("keyword", keyword);
            response.put("currentPage", messages.getNumber());
            response.put("totalPages", messages.getTotalPages());
            response.put("totalElements", messages.getTotalElements());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("搜索消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取未读消息数量
     */
    @GetMapping("/unread-count")
    public ResponseEntity<?> getUnreadCount(
            @RequestParam(required = false) Long chatRoomId,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            
            Map<String, Object> response = new HashMap<>();
            
            if (chatRoomId != null) {
                // 获取指定聊天室的未读数量
                Long unreadCount = messageService.getUnreadMessageCount(chatRoomId, currentUser.getId());
                response.put("chatRoomId", chatRoomId);
                response.put("unreadCount", unreadCount);
            } else {
                // 获取总未读数量
                Long totalUnreadCount = messageService.getTotalUnreadCount(currentUser.getId());
                response.put("totalUnreadCount", totalUnreadCount);
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取未读消息数量失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取消息统计信息
     */
    @GetMapping("/stats/{chatRoomId}")
    public ResponseEntity<?> getMessageStats(@PathVariable Long chatRoomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            MessageService.MessageStats stats = messageService.getMessageStats(chatRoomId, currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRoomId", chatRoomId);
            response.put("totalCount", stats.getTotalCount());
            response.put("unreadCount", stats.getUnreadCount());
            response.put("lastMessage", MessageDto.fromEntity(stats.getLastMessage()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取消息统计失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // DTO 类
    public static class SendMessageRequest {
        private Long chatRoomId;
        private String content;
        private Message.MessageType messageType;
        private String encryptedContent;
        private Integer encryptionVersion;
        private Boolean isAnonymous;
        private Long stickerId;
        
        // Getters and Setters
        public Long getChatRoomId() { return chatRoomId; }
        public void setChatRoomId(Long chatRoomId) { this.chatRoomId = chatRoomId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Message.MessageType getMessageType() { return messageType; }
        public void setMessageType(Message.MessageType messageType) { this.messageType = messageType; }
        public String getEncryptedContent() { return encryptedContent; }
        public void setEncryptedContent(String encryptedContent) { this.encryptedContent = encryptedContent; }
        public Integer getEncryptionVersion() { return encryptionVersion; }
        public void setEncryptionVersion(Integer encryptionVersion) { this.encryptionVersion = encryptionVersion; }
        public Boolean getIsAnonymous() { return isAnonymous; }
        public void setIsAnonymous(Boolean isAnonymous) { this.isAnonymous = isAnonymous; }
        public Long getStickerId() { return stickerId; }
        public void setStickerId(Long stickerId) { this.stickerId = stickerId; }
    }

    public static class ReplyMessageRequest {
        private Long chatRoomId;
        private Long replyToMessageId;
        private String content;
        
        // Getters and Setters
        public Long getChatRoomId() { return chatRoomId; }
        public void setChatRoomId(Long chatRoomId) { this.chatRoomId = chatRoomId; }
        public Long getReplyToMessageId() { return replyToMessageId; }
        public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    private boolean isImageFile(String fileName, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return true;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg")
            || lower.endsWith(".jpeg")
            || lower.endsWith(".png")
            || lower.endsWith(".gif")
            || lower.endsWith(".webp");
    }

    private Message.MessageType parseAttachmentType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Message.MessageType type = Message.MessageType.valueOf(value.trim().toUpperCase());
        if (type != Message.MessageType.IMAGE
                && type != Message.MessageType.FILE
                && type != Message.MessageType.VOICE
                && type != Message.MessageType.AUDIO
                && type != Message.MessageType.VIDEO) {
            throw new IllegalArgumentException("仅支持附件消息类型");
        }
        return type;
    }

    private Message.MessageType inferAttachmentMessageType(String fileName, String contentType) {
        if (isImageFile(fileName, contentType)) {
            return Message.MessageType.IMAGE;
        }
        if (contentType != null) {
            String lowerType = contentType.toLowerCase();
            if (lowerType.startsWith("audio/")) {
                return Message.MessageType.VOICE;
            }
            if (lowerType.startsWith("video/")) {
                return Message.MessageType.VIDEO;
            }
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".webm")) {
            return Message.MessageType.VOICE;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return Message.MessageType.VIDEO;
        }
        return Message.MessageType.FILE;
    }

    private Message.MessageType normalizeAttachmentMessageType(Message.MessageType requested) {
        return switch (requested) {
            case IMAGE, FILE, VOICE, AUDIO, VIDEO -> requested;
            default -> throw new IllegalArgumentException("不支持的附件消息类型: " + requested);
        };
    }

    private List<MessageDto> toMessageDtos(List<Message> messages) {
        return messages.stream()
                .map(MessageDto::fromEntity)
                .collect(Collectors.toList());
    }

    private List<MessageDto> toMessageDtos(List<Message> messages, Long currentUserId) {
        return messageReactionService.attachAggregates(toMessageDtos(messages), currentUserId);
    }

    private void processBotsAndBroadcast(Message message, Long senderId) {
        if (message.getMessageType() != Message.MessageType.TEXT) {
            return;
        }
        List<Message> botMessages = botService.processMessageForBots(
                message.getChatRoom().getId(),
                message.getContent(),
                senderId);
        botMessages.forEach(rawWebSocketHandler::broadcastMessage);
    }
}
