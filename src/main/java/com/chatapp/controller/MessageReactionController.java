package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.MessageDto;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.service.MessageReactionService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/messages/{messageId}/reactions")
@RequiredArgsConstructor
public class MessageReactionController {

    private final MessageReactionService reactionService;
    private final MessageRepository messageRepository;
    private final UserService userService;
    private final RawWebSocketHandler webSocketHandler;

    @PostMapping
    public ResponseEntity<ApiResponse<List<MessageDto.ReactionInfo>>> add(
            @PathVariable Long messageId,
            @RequestBody ReactionRequest request,
            Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        List<MessageDto.ReactionInfo> reactions =
                reactionService.addReaction(messageId, user.getId(), request.getEmoji());
        broadcast(messageId, request.getEmoji(), reactions, user.getId(), true);
        return ResponseEntity.ok(ApiResponse.success(reactions));
    }

    @DeleteMapping("/{emoji}")
    public ResponseEntity<ApiResponse<List<MessageDto.ReactionInfo>>> remove(
            @PathVariable Long messageId,
            @PathVariable String emoji,
            Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        List<MessageDto.ReactionInfo> reactions =
                reactionService.removeReaction(messageId, user.getId(), emoji);
        broadcast(messageId, emoji, reactions, user.getId(), false);
        return ResponseEntity.ok(ApiResponse.success(reactions));
    }

    private void broadcast(Long messageId, String emoji, List<MessageDto.ReactionInfo> reactions,
                           Long userId, boolean added) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getChatRoom() == null) return;
        webSocketHandler.broadcastReactionChanged(
                message.getChatRoom().getId(),
                messageId,
                emoji,
                reactions,
                userId,
                added);
    }

    @Data
    public static class ReactionRequest {
        private String emoji;
    }
}
