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

        List<MessageDto> messages = page.getContent().stream()
                .map(MessageDto::fromEntity)
                .toList();
        List<Map<String, Object>> results = page.getContent().stream()
                .map(message -> resultFor(roomId, message))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("messages", messages);
        response.put("results", results);
        response.put("keyword", query);
        response.put("limit", safeLimit);
        response.put("offset", safeOffset);
        response.put("totalElements", page.getTotalElements());
        response.put("hasNext", page.hasNext());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> resultFor(Long roomId, Message message) {
        Map<String, Object> result = new HashMap<>();
        result.put("messageId", message.getId());
        result.put("content", message.getContent());
        result.put("senderName", MessageDto.fromEntity(message).getSenderName());
        result.put("timestamp", message.getCreatedAt());
        result.put("message", MessageDto.fromEntity(message));
        result.put("beforeAfterContext", messageService.searchContext(roomId, message));
        return result;
    }
}
