package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.service.MessageService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/chat-rooms", "/api/chat-rooms"})
@RequiredArgsConstructor
public class ChatRoomMessageSearchController {

    private final MessageService messageService;
    private final UserService userService;

    @GetMapping("/{roomId}/messages/search")
    public ResponseEntity<?> searchMessagesInRoom(
            @PathVariable Long roomId,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication auth) {
        User currentUser = userService.findUserByUsername(auth.getName());
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeOffset = Math.max(0, offset);
        Pageable pageable = PageRequest.of(
                safeOffset / safeLimit,
                safeLimit,
                Sort.by("createdAt").descending());
        Page<Message> page = messageService.searchMessages(
                roomId,
                currentUser.getId(),
                query == null ? "" : query.trim(),
                pageable);

        Long viewerId = currentUser.getId();
        List<MessageDto> messages = page.getContent().stream()
                .map(message -> MessageDto.fromEntity(message, viewerId))
                .toList();
        List<Map<String, Object>> results = page.getContent().stream()
                .map(message -> resultFor(roomId, message, viewerId))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("messages", messages);
        response.put("results", results);
        response.put("keyword", query);
        response.put("limit", safeLimit);
        response.put("offset", safeOffset);
        response.put("currentPage", page.getNumber());
        response.put("totalPages", page.getTotalPages());
        response.put("totalElements", page.getTotalElements());
        response.put("hasNext", page.hasNext());
        response.put("hasPrevious", page.hasPrevious());
        return ResponseEntity.ok(response);
    }

    /**
     * 消息列表页的全局搜索：在当前用户所在的所有会话里按关键词搜消息，
     * 每条结果都带 chatRoomId，客户端据此跳转到对应会话。
     */
    @GetMapping("/messages/search")
    public ResponseEntity<?> searchMessagesAcrossRooms(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "关键词不能为空"));
        }
        User currentUser = userService.findUserByUsername(auth.getName());
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
        Page<Message> result = messageService.searchMessagesAcrossRooms(currentUser.getId(), keyword, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("messages", result.getContent().stream()
                .map(message -> MessageDto.fromEntity(message, currentUser.getId()))
                .toList());
        response.put("keyword", keyword);
        response.put("currentPage", result.getNumber());
        response.put("totalPages", result.getTotalPages());
        response.put("totalElements", result.getTotalElements());
        response.put("hasNext", result.hasNext());
        response.put("hasPrevious", result.hasPrevious());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> resultFor(Long roomId, Message message, Long viewerId) {
        MessageDto dto = MessageDto.fromEntity(message, viewerId);
        Map<String, Object> result = new HashMap<>();
        result.put("messageId", message.getId());
        result.put("content", message.getContent());
        result.put("senderName", dto.getSenderName());
        result.put("timestamp", message.getCreatedAt());
        result.put("message", dto);
        result.put("beforeAfterContext", messageService.searchContext(roomId, message, viewerId));
        return result;
    }
}
