package com.chatapp.controller;

import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.service.MessageService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 发送文本消息
     */
    @PostMapping
    public ResponseEntity<?> sendMessage(@RequestBody SendMessageRequest request, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            Message message = messageService.sendMessage(
                currentUser.getId(),
                request.getChatRoomId(),
                request.getContent(),
                Message.MessageType.TEXT
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "消息发送成功");
            response.put("data", message);
            
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
    public ResponseEntity<?> sendFileMessage(@RequestBody SendFileMessageRequest request, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            
            Message.MessageType messageType = Message.MessageType.FILE;
            if (request.getFileType() != null) {
                if (request.getFileType().startsWith("image/")) {
                    messageType = Message.MessageType.IMAGE;
                } else if (request.getFileType().startsWith("video/")) {
                    messageType = Message.MessageType.VIDEO;
                } else if (request.getFileType().startsWith("audio/")) {
                    messageType = Message.MessageType.AUDIO;
                }
            }
            
            Message message = messageService.sendFileMessage(
                currentUser.getId(),
                request.getChatRoomId(),
                request.getFileName(),
                request.getFileUrl(),
                request.getFileType(),
                request.getFileSize(),
                messageType
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "文件消息发送成功");
            response.put("data", message);
            
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
            User currentUser = userService.findByUsername(auth.getName());
            Message message = messageService.replyToMessage(
                currentUser.getId(),
                request.getChatRoomId(),
                request.getReplyToMessageId(),
                request.getContent(),
                Message.MessageType.TEXT
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "回复消息发送成功");
            response.put("data", message);
            
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
            User currentUser = userService.findByUsername(auth.getName());
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<Message> messages = messageService.getChatRoomMessages(chatRoomId, currentUser.getId(), pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", messages.getContent());
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
            User currentUser = userService.findByUsername(auth.getName());
            List<Message> messages = messageService.getRecentMessages(chatRoomId, currentUser.getId(), limit);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", messages);
            response.put("count", messages.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取最新消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 标记消息为已读
     */
    @PostMapping("/{messageId}/read")
    public ResponseEntity<?> markMessageAsRead(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            messageService.markMessageAsRead(messageId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "消息已标记为已读"));
        } catch (Exception e) {
            log.error("标记消息已读失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 标记聊天室所有消息为已读
     */
    @PostMapping("/chat-room/{chatRoomId}/read-all")
    public ResponseEntity<?> markAllMessagesAsRead(@PathVariable Long chatRoomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            messageService.markAllMessagesAsRead(chatRoomId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "所有消息已标记为已读"));
        } catch (Exception e) {
            log.error("标记所有消息已读失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 撤回消息
     */
    @PostMapping("/{messageId}/recall")
    public ResponseEntity<?> recallMessage(@PathVariable Long messageId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            messageService.recallMessage(messageId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "消息已撤回"));
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
            User currentUser = userService.findByUsername(auth.getName());
            messageService.deleteMessage(messageId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "消息已删除"));
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
            User currentUser = userService.findByUsername(auth.getName());
            
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<Message> messages = messageService.searchMessages(chatRoomId, currentUser.getId(), keyword, pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("messages", messages.getContent());
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
            User currentUser = userService.findByUsername(auth.getName());
            
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
            User currentUser = userService.findByUsername(auth.getName());
            MessageService.MessageStats stats = messageService.getMessageStats(chatRoomId, currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRoomId", chatRoomId);
            response.put("totalCount", stats.getTotalCount());
            response.put("unreadCount", stats.getUnreadCount());
            response.put("lastMessage", stats.getLastMessage());
            
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
        
        // Getters and Setters
        public Long getChatRoomId() { return chatRoomId; }
        public void setChatRoomId(Long chatRoomId) { this.chatRoomId = chatRoomId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class SendFileMessageRequest {
        private Long chatRoomId;
        private String fileName;
        private String fileUrl;
        private String fileType;
        private Long fileSize;
        
        // Getters and Setters
        public Long getChatRoomId() { return chatRoomId; }
        public void setChatRoomId(Long chatRoomId) { this.chatRoomId = chatRoomId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileUrl() { return fileUrl; }
        public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
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
} 