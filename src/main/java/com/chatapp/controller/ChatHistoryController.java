package com.chatapp.controller;

import com.chatapp.entity.ChatHistory;
import com.chatapp.service.ChatHistoryService;
import com.chatapp.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天记录控制器
 */
@RestController
@RequestMapping("/api/chat-history")
@CrossOrigin(origins = "*")
public class ChatHistoryController {

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 发送私聊文本消息
     */
    @PostMapping("/private/text")
    public ResponseEntity<?> sendPrivateTextMessage(
            @RequestParam("receiverId") Long receiverId,
            @RequestParam("content") String content,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory message = chatHistoryService.sendPrivateMessage(senderId, receiverId, content);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "消息发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 发送群聊文本消息
     */
    @PostMapping("/group/text")
    public ResponseEntity<?> sendGroupTextMessage(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("content") String content,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory message = chatHistoryService.sendGroupMessage(senderId, chatRoomId, content);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "消息发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 发送私聊文件消息
     */
    @PostMapping("/private/file")
    public ResponseEntity<?> sendPrivateFileMessage(
            @RequestParam("receiverId") Long receiverId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("messageType") String messageType,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory.MessageType type = ChatHistory.MessageType.valueOf(messageType.toUpperCase());
            ChatHistory message = chatHistoryService.sendPrivateFileMessage(senderId, receiverId, file, type);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文件发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return createErrorResponse("文件上传失败: " + e.getMessage());
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 发送群聊文件消息
     */
    @PostMapping("/group/file")
    public ResponseEntity<?> sendGroupFileMessage(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("messageType") String messageType,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory.MessageType type = ChatHistory.MessageType.valueOf(messageType.toUpperCase());
            ChatHistory message = chatHistoryService.sendGroupFileMessage(senderId, chatRoomId, file, type);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "文件发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return createErrorResponse("文件上传失败: " + e.getMessage());
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 回复私聊消息
     */
    @PostMapping("/private/reply")
    public ResponseEntity<?> replyToPrivateMessage(
            @RequestParam("receiverId") Long receiverId,
            @RequestParam("content") String content,
            @RequestParam("replyToId") Long replyToId,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory message = chatHistoryService.replyToPrivateMessage(senderId, receiverId, content, replyToId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "回复发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 回复群聊消息
     */
    @PostMapping("/group/reply")
    public ResponseEntity<?> replyToGroupMessage(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("content") String content,
            @RequestParam("replyToId") Long replyToId,
            HttpServletRequest request) {
        try {
            Long senderId = getCurrentUserId(request);
            ChatHistory message = chatHistoryService.replyToGroupMessage(senderId, chatRoomId, content, replyToId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "回复发送成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取私聊历史记录
     */
    @GetMapping("/private")
    public ResponseEntity<?> getPrivateChatHistory(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        try {
            Long currentUserId = getCurrentUserId(request);
            Page<ChatHistory> messages = chatHistoryService.getPrivateChatHistory(currentUserId, userId, page, size);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages.getContent());
            response.put("totalElements", messages.getTotalElements());
            response.put("totalPages", messages.getTotalPages());
            response.put("currentPage", page);
            response.put("pageSize", size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取群聊历史记录
     */
    @GetMapping("/group")
    public ResponseEntity<?> getGroupChatHistory(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            Page<ChatHistory> messages = chatHistoryService.getGroupChatHistory(chatRoomId, page, size);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages.getContent());
            response.put("totalElements", messages.getTotalElements());
            response.put("totalPages", messages.getTotalPages());
            response.put("currentPage", page);
            response.put("pageSize", size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取私聊最新消息
     */
    @GetMapping("/private/latest")
    public ResponseEntity<?> getLatestPrivateMessages(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest request) {
        try {
            Long currentUserId = getCurrentUserId(request);
            List<ChatHistory> messages = chatHistoryService.getLatestPrivateMessages(currentUserId, userId, limit);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取群聊最新消息
     */
    @GetMapping("/group/latest")
    public ResponseEntity<?> getLatestGroupMessages(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            List<ChatHistory> messages = chatHistoryService.getLatestGroupMessages(chatRoomId, limit);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 搜索私聊消息
     */
    @GetMapping("/private/search")
    public ResponseEntity<?> searchPrivateMessages(
            @RequestParam("userId") Long userId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        try {
            Long currentUserId = getCurrentUserId(request);
            Page<ChatHistory> messages = chatHistoryService.searchPrivateMessages(currentUserId, userId, keyword, page, size);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages.getContent());
            response.put("totalElements", messages.getTotalElements());
            response.put("totalPages", messages.getTotalPages());
            response.put("currentPage", page);
            response.put("pageSize", size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 搜索群聊消息
     */
    @GetMapping("/group/search")
    public ResponseEntity<?> searchGroupMessages(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            Page<ChatHistory> messages = chatHistoryService.searchGroupMessages(chatRoomId, keyword, page, size);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages.getContent());
            response.put("totalElements", messages.getTotalElements());
            response.put("totalPages", messages.getTotalPages());
            response.put("currentPage", page);
            response.put("pageSize", size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 撤回消息
     */
    @PutMapping("/recall/{messageId}")
    public ResponseEntity<?> recallMessage(
            @PathVariable("messageId") Long messageId,
            HttpServletRequest request) {
        try {
            Long userId = getCurrentUserId(request);
            ChatHistory message = chatHistoryService.recallMessage(messageId, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "消息撤回成功");
            response.put("data", message);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 删除消息
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<?> deleteMessage(
            @PathVariable("messageId") Long messageId,
            HttpServletRequest request) {
        try {
            Long userId = getCurrentUserId(request);
            chatHistoryService.deleteMessage(messageId, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "消息删除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取消息详情
     */
    @GetMapping("/{messageId}")
    public ResponseEntity<?> getMessageById(
            @PathVariable("messageId") Long messageId,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            Optional<ChatHistory> message = chatHistoryService.getMessageById(messageId);
            
            if (message.isPresent()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", message.get());
                return ResponseEntity.ok(response);
            } else {
                return createErrorResponse("消息不存在");
            }
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 统计私聊消息数量
     */
    @GetMapping("/private/count")
    public ResponseEntity<?> countPrivateMessages(
            @RequestParam("userId") Long userId,
            HttpServletRequest request) {
        try {
            Long currentUserId = getCurrentUserId(request);
            long count = chatHistoryService.countPrivateMessages(currentUserId, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of("count", count));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 统计群聊消息数量
     */
    @GetMapping("/group/count")
    public ResponseEntity<?> countGroupMessages(
            @RequestParam("chatRoomId") Long chatRoomId,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            long count = chatHistoryService.countGroupMessages(chatRoomId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", Map.of("count", count));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取指定时间范围内的私聊消息
     */
    @GetMapping("/private/time-range")
    public ResponseEntity<?> getPrivateMessagesInTimeRange(
            @RequestParam("userId") Long userId,
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            HttpServletRequest request) {
        try {
            Long currentUserId = getCurrentUserId(request);
            List<ChatHistory> messages = chatHistoryService.getPrivateMessagesInTimeRange(currentUserId, userId, startTime, endTime);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取指定时间范围内的群聊消息
     */
    @GetMapping("/group/time-range")
    public ResponseEntity<?> getGroupMessagesInTimeRange(
            @RequestParam("chatRoomId") Long chatRoomId,
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            HttpServletRequest request) {
        try {
            getCurrentUserId(request); // 验证登录状态
            List<ChatHistory> messages = chatHistoryService.getGroupMessagesInTimeRange(chatRoomId, startTime, endTime);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", messages);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return createErrorResponse(e.getMessage());
        }
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            String username = jwtUtil.getUsernameFromToken(token);
            // TODO: 实现通过用户名获取用户ID的逻辑
            return 1L; // 暂时返回硬编码值
        }
        throw new RuntimeException("未找到有效的认证信息");
    }

    /**
     * 创建错误响应
     */
    private ResponseEntity<?> createErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }
} 