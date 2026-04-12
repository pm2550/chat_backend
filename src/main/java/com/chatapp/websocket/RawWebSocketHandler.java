package com.chatapp.websocket;

import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.service.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket handler that speaks the Flutter client's JSON-framed protocol:
 *   ← {"type":"message","chatRoomId":1,"content":"hi","messageType":"TEXT"}
 *   ← {"type":"typing","chatRoomId":1,"isTyping":true}
 *   ← {"type":"ping"}
 *   → {"type":"message","message":{...MessageDto...}}
 *   → {"type":"typing","chatRoomId":1,"userId":2,"isTyping":true}
 *   → {"type":"status","userId":2,"onlineStatus":"ONLINE"}
 *   → {"type":"pong"}
 *
 * The handshake is gated by JwtHandshakeInterceptor which resolves the user
 * from a ?token=... query parameter and puts a User in the session attributes.
 * STOMP is still wired in WebSocketConfig for legacy clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_USER = "user";

    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final ChatRoomRepository chatRoomRepository;

    // userId -> sessions (a user may have multiple devices connected)
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        User user = (User) session.getAttributes().get(ATTR_USER);
        if (user == null) {
            closeQuiet(session, CloseStatus.POLICY_VIOLATION.withReason("unauthenticated"));
            return;
        }
        userSessions.computeIfAbsent(user.getId(),
                id -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("WebSocket connected: userId={}, sessionId={}", user.getId(), session.getId());
        broadcastStatus(user.getId(), "ONLINE");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        User user = (User) session.getAttributes().get(ATTR_USER);
        if (user == null) {
            return;
        }
        Set<WebSocketSession> sessions = userSessions.get(user.getId());
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                userSessions.remove(user.getId());
                broadcastStatus(user.getId(), "OFFLINE");
            }
        }
        log.info("WebSocket closed: userId={}, sessionId={}, status={}",
                user.getId(), session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        User user = (User) session.getAttributes().get(ATTR_USER);
        if (user == null) {
            closeQuiet(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(textMessage.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "ping" -> sendJson(session, Map.of("type", "pong"));
                case "message" -> handleIncomingMessage(user, root);
                case "typing" -> handleTyping(user, root);
                default -> log.debug("Unknown ws message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Failed to handle ws message: {}", e.getMessage());
            sendJson(session, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error: {}", exception.getMessage());
        closeQuiet(session, CloseStatus.SERVER_ERROR);
    }

    private void handleIncomingMessage(User user, JsonNode root) {
        // Accept either {type:"message", message:{chatRoomId,content,...}} or flat form
        JsonNode payload = root.has("message") ? root.get("message") : root;
        Long chatRoomId = parseLong(payload.get("chatRoomId"));
        if (chatRoomId == null) {
            chatRoomId = parseLong(payload.get("chat_room_id"));
        }
        String content = payload.path("content").asText("");
        String messageTypeStr = payload.path("messageType").asText(payload.path("type").asText("TEXT"));
        if ("message".equals(messageTypeStr)) {
            messageTypeStr = "TEXT";
        }
        Message.MessageType messageType;
        try {
            messageType = Message.MessageType.valueOf(messageTypeStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            messageType = Message.MessageType.TEXT;
        }
        if (chatRoomId == null) {
            log.warn("ws message missing chatRoomId from user {}", user.getId());
            return;
        }
        Message saved = messageService.sendMessage(user.getId(), chatRoomId, content, messageType);
        broadcastMessage(saved);
    }

    private void handleTyping(User user, JsonNode root) {
        Long chatRoomId = parseLong(root.get("chatRoomId"));
        boolean isTyping = root.path("isTyping").asBoolean(false);
        if (chatRoomId == null) {
            return;
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("type", "typing");
        out.put("chatRoomId", chatRoomId);
        out.put("userId", user.getId());
        out.put("isTyping", isTyping);
        broadcastToRoomExcept(chatRoomId, user.getId(), out);
    }

    private void broadcastMessage(Message saved) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "message");
        envelope.set("message", toMessageJson(saved));
        broadcastToRoom(saved.getChatRoom().getId(), envelope);
    }

    private void broadcastStatus(Long userId, String status) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "status");
        envelope.put("userId", userId);
        envelope.put("onlineStatus", status);
        // Broadcast to all connected users except the originator
        userSessions.forEach((uid, sessions) -> {
            if (!uid.equals(userId)) {
                sessions.forEach(s -> sendJson(s, envelope));
            }
        });
    }

    private void broadcastToRoom(Long chatRoomId, Object payload) {
        roomMembers(chatRoomId).forEach(userId -> {
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.forEach(s -> sendJson(s, payload));
            }
        });
    }

    private void broadcastToRoomExcept(Long chatRoomId, Long exceptUserId, Object payload) {
        roomMembers(chatRoomId).forEach(userId -> {
            if (userId.equals(exceptUserId)) return;
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.forEach(s -> sendJson(s, payload));
            }
        });
    }

    private Iterable<Long> roomMembers(Long chatRoomId) {
        List<Long> ids = chatRoomRepository.findMemberUserIdsByRoomId(chatRoomId);
        return ids != null ? ids : List.of();
    }

    private ObjectNode toMessageJson(Message m) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("id", m.getId());
        json.put("content", m.getContent());
        json.put("chatRoomId", m.getChatRoom().getId());
        json.put("senderId", m.getSender().getId());
        json.put("senderName", m.getSender().getDisplayName() != null
                ? m.getSender().getDisplayName()
                : m.getSender().getUsername());
        if (m.getSender().getAvatarUrl() != null) {
            json.put("senderAvatar", m.getSender().getAvatarUrl());
        }
        json.put("type", m.getMessageType() != null ? m.getMessageType().name() : "TEXT");
        json.put("status", m.getMessageStatus() != null ? m.getMessageStatus().name() : "SENT");
        if (m.getCreatedAt() != null) {
            json.put("timestamp", m.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (m.getFileUrl() != null) json.put("fileUrl", m.getFileUrl());
        if (m.getFileName() != null) json.put("fileName", m.getFileName());
        if (m.getFileSize() != null) json.put("fileSize", m.getFileSize());
        if (m.getFileType() != null) json.put("fileType", m.getFileType());
        return json;
    }

    private void sendJson(WebSocketSession session, Object payload) {
        if (!session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (IOException e) {
            log.warn("Failed to send ws message: {}", e.getMessage());
        }
    }

    private void closeQuiet(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }

    private Long parseLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // Test visibility
    public int connectedUserCount() {
        return userSessions.size();
    }
}
