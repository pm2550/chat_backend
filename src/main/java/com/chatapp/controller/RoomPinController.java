package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.service.MessageService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Slf4j
public class RoomPinController {
    private final MessageService messageService;
    private final UserService userService;
    private final RawWebSocketHandler rawWebSocketHandler;

    @PostMapping("/{roomId}/pin/{messageId}")
    public ResponseEntity<?> pinMessage(
            @PathVariable Long roomId,
            @PathVariable Long messageId,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            messageService.pinMessage(roomId, messageId, currentUser.getId());
            List<MessageDto> pins = pinnedDtos(roomId, currentUser.getId());
            rawWebSocketHandler.broadcastMessageAction(roomId, "pin_added", Map.of(
                    "messageId", messageId,
                    "pins", pins));
            return ResponseEntity.ok(Map.of("message", "消息已置顶", "data", pins));
        } catch (Exception e) {
            log.error("置顶消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{roomId}/pin/{messageId}")
    public ResponseEntity<?> unpinMessage(
            @PathVariable Long roomId,
            @PathVariable Long messageId,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            messageService.unpinMessage(roomId, messageId, currentUser.getId());
            List<MessageDto> pins = pinnedDtos(roomId, currentUser.getId());
            rawWebSocketHandler.broadcastMessageAction(roomId, "pin_removed", Map.of(
                    "messageId", messageId,
                    "pins", pins));
            return ResponseEntity.ok(Map.of("message", "消息已取消置顶", "data", pins));
        } catch (Exception e) {
            log.error("取消置顶消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{roomId}/pins")
    public ResponseEntity<?> getPins(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            return ResponseEntity.ok(Map.of(
                    "message", "置顶消息",
                    "data", pinnedDtos(roomId, currentUser.getId())
            ));
        } catch (Exception e) {
            log.error("获取置顶消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<MessageDto> pinnedDtos(Long roomId, Long userId) {
        List<Message> messages = messageService.getPinnedMessages(roomId, userId);
        return messages.stream().map(MessageDto::fromEntity).toList();
    }
}
