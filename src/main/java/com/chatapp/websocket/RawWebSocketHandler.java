package com.chatapp.websocket;

import com.chatapp.dto.MessageDto;
import com.chatapp.dto.PollDto;
import com.chatapp.entity.AppVersion;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.service.BotService;
import com.chatapp.service.BotReplyDeliveryService;
import com.chatapp.service.MessageService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.RoomTypingAggregator;
import com.chatapp.service.tool.PendingClientCallRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket handler that speaks the Flutter client's JSON-framed protocol:
 *   ← {"type":"message","chatRoomId":1,"content":"hi","messageType":"TEXT"}
 *   ← {"type":"typing","chatRoomId":1,"isTyping":true}
 *   ← {"type":"call","action":"invite|accept|reject|offer|answer|ice|hangup",...}
 *   ← {"type":"agent_tool_result","callId":"...","result":{...}}
 *   ← {"type":"ping"}
 *   → {"type":"message","message":{...MessageDto...}}
 *   → {"type":"typing","chatRoomId":1,"userId":2,"isTyping":true}
 *   → {"type":"call","action":"offer|answer|ice|hangup",...}
 *   → {"type":"agent_tool_request","callId":"...","toolName":"...","params":{...}}
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
    private final BotService botService;
    private final BotReplyDeliveryService botReplyDeliveryService;
    private final ChatRoomRepository chatRoomRepository;
    private final PushNotificationService pushNotificationService;
    private final RoomTypingAggregator roomTypingAggregator;
    private final CallRoomRegistry callRoomRegistry;
    private final PendingClientCallRegistry pendingClientCallRegistry;

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
                case "read", "read_receipt" -> handleReadReceipt(user, root);
                case "call" -> handleCallSignal(user, root);
                case "agent_tool_result" -> handleAgentToolResult(user, root);
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
        String encryptedContent = payload.path("encryptedContent").asText(null);
        Integer encryptionVersion = payload.has("encryptionVersion")
                ? payload.path("encryptionVersion").asInt(1)
                : null;
        boolean anonymous = payload.path("isAnonymous").asBoolean(false);
        Message saved = anonymous
                ? messageService.sendAnonymousEncryptedMessage(
                        user.getId(),
                        chatRoomId,
                        content,
                        encryptedContent,
                        encryptionVersion,
                        messageType)
                : messageService.sendEncryptedMessage(
                        user.getId(),
                        chatRoomId,
                        content,
                        encryptedContent,
                        encryptionVersion,
                        messageType);
        broadcastMessage(saved);
        if (messageType == Message.MessageType.TEXT
                && (encryptedContent == null || encryptedContent.isBlank())) {
            botReplyDeliveryService.deliver(
                    botService.processMessageForBots(chatRoomId, content, user.getId()),
                    this::broadcastMessage);
        }
    }

    private void handleTyping(User user, JsonNode root) {
        Long chatRoomId = parseLong(root.get("chatRoomId"));
        boolean isTyping = root.path("isTyping").asBoolean(false);
        if (chatRoomId == null) {
            return;
        }
        if (!chatRoomRepository.isMember(chatRoomId, user.getId())) {
            return;
        }
        roomTypingAggregator.update(
                chatRoomId,
                user.getId(),
                fallback(user.getDisplayName(), user.getUsername()),
                isTyping);
    }

    @Scheduled(fixedRate = 1000)
    public void flushTypingAggregates() {
        for (RoomTypingAggregator.TypingSnapshot snapshot : roomTypingAggregator.drainChangedSnapshots()) {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("type", "typing_aggregated");
            out.put("chatRoomId", snapshot.getRoomId());
            out.set("userIds", objectMapper.valueToTree(snapshot.getUserIds()));
            out.set("userNames", objectMapper.valueToTree(snapshot.getUserNames()));
            broadcastToRoom(snapshot.getRoomId(), out);

            ObjectNode legacy = out.deepCopy();
            legacy.put("type", "typing");
            broadcastToRoom(snapshot.getRoomId(), legacy);
        }
    }

    private void handleReadReceipt(User user, JsonNode root) {
        Long chatRoomId = parseLong(root.get("chatRoomId"));
        if (chatRoomId == null) {
            return;
        }
        Message lastMessage = messageService.markAllMessagesAsRead(chatRoomId, user.getId());
        broadcastReadReceipt(
                chatRoomId,
                user.getId(),
                lastMessage != null ? lastMessage.getId() : null);
    }

    private void handleCallSignal(User user, JsonNode root) {
        Long chatRoomId = parseLong(root.get("chatRoomId"));
        if (chatRoomId == null) {
            chatRoomId = parseLong(root.get("chat_room_id"));
        }
        if (chatRoomId == null) {
            log.warn("call signal missing chatRoomId from user {}", user.getId());
            return;
        }

        List<Long> members = chatRoomRepository.findMemberUserIdsByRoomId(chatRoomId);
        if (members == null || !members.contains(user.getId())) {
            log.warn("user {} tried to signal call in room {} without membership",
                    user.getId(), chatRoomId);
            return;
        }

        Long toUserId = parseLong(root.get("toUserId"));
        if (toUserId == null) {
            toUserId = parseLong(root.get("to_user_id"));
        }
        if (toUserId != null && !members.contains(toUserId)) {
            log.warn("user {} tried to signal call to non-member {} in room {}",
                    user.getId(), toUserId, chatRoomId);
            return;
        }

        String action = root.path("action").asText("");
        String callId = root.path("callId").asText("");

        if ("join".equals(action)) {
            handleCallJoin(user, chatRoomId, callId);
            return;
        }
        if ("leave".equals(action)) {
            handleCallLeave(user, chatRoomId, callId);
            return;
        }

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "call");
        envelope.put("action", action);
        envelope.put("chatRoomId", chatRoomId);
        envelope.put("fromUserId", user.getId());
        envelope.put("fromName", fallback(user.getDisplayName(), user.getUsername()));
        if (toUserId != null) {
            envelope.put("toUserId", toUserId);
        }
        copyText(root, envelope, "callId");
        copyText(root, envelope, "mediaType");
        copyText(root, envelope, "sdpType");
        copyText(root, envelope, "sdp");
        if (root.has("candidate")) {
            envelope.set("candidate", root.get("candidate"));
        }

        if (toUserId != null) {
            boolean delivered = sendToUser(toUserId, envelope);
            if ("invite".equals(action)) {
                if (delivered) {
                    sendCallRinging(user.getId(), toUserId, chatRoomId, callId,
                            root.path("mediaType").asText("AUDIO"));
                } else {
                    pushOfflineCallInvitation(toUserId, user, chatRoomId, callId,
                            root.path("mediaType").asText("AUDIO"));
                }
            }
        } else {
            broadcastToRoomExcept(chatRoomId, user.getId(), envelope);
        }
    }

    private void sendCallRinging(Long callerUserId, Long targetUserId, Long chatRoomId,
                                 String callId, String mediaType) {
        ObjectNode ringing = objectMapper.createObjectNode();
        ringing.put("type", "call");
        ringing.put("action", "call_ringing");
        ringing.put("chatRoomId", chatRoomId);
        ringing.put("callId", callId);
        ringing.put("fromUserId", targetUserId);
        ringing.put("toUserId", callerUserId);
        ringing.put("mediaType", mediaType);
        sendToUser(callerUserId, ringing);
    }

    private void handleCallJoin(User user, Long chatRoomId, String callId) {
        if (callId == null || callId.isBlank()) {
            sendCallError(user.getId(), chatRoomId, callId, "MISSING_CALL_ID", 0);
            return;
        }

        CallRoomRegistry.JoinResult result = callRoomRegistry.addParticipant(callId, user.getId());
        if (!result.accepted()) {
            sendCallError(user.getId(), chatRoomId, callId, "ROOM_FULL", result.participants().size());
            return;
        }

        ObjectNode accepted = objectMapper.createObjectNode();
        accepted.put("type", "call");
        accepted.put("action", "join_accepted");
        accepted.put("chatRoomId", chatRoomId);
        accepted.put("callId", callId);
        accepted.put("fromUserId", user.getId());
        accepted.put("fromName", fallback(user.getDisplayName(), user.getUsername()));
        accepted.put("current", result.participants().size());
        accepted.put("max", CallRoomRegistry.MAX_PARTICIPANTS);
        accepted.set("existingParticipantIds", objectMapper.valueToTree(result.existingParticipants()));
        accepted.set("participantIds", objectMapper.valueToTree(result.participants()));
        sendToUser(user.getId(), accepted);

        ObjectNode joined = objectMapper.createObjectNode();
        joined.put("type", "call");
        joined.put("action", "participant_joined");
        joined.put("chatRoomId", chatRoomId);
        joined.put("callId", callId);
        joined.put("fromUserId", user.getId());
        joined.put("fromName", fallback(user.getDisplayName(), user.getUsername()));
        joined.put("userId", user.getId());
        joined.put("name", fallback(user.getDisplayName(), user.getUsername()));
        joined.put("current", result.participants().size());
        joined.put("max", CallRoomRegistry.MAX_PARTICIPANTS);
        sendToUsersExcept(result.participants(), user.getId(), joined);
    }

    private void handleCallLeave(User user, Long chatRoomId, String callId) {
        if (callId == null || callId.isBlank()) {
            return;
        }

        List<Long> remaining = callRoomRegistry.removeParticipant(callId, user.getId());
        ObjectNode left = objectMapper.createObjectNode();
        left.put("type", "call");
        left.put("action", "participant_left");
        left.put("chatRoomId", chatRoomId);
        left.put("callId", callId);
        left.put("fromUserId", user.getId());
        left.put("fromName", fallback(user.getDisplayName(), user.getUsername()));
        left.put("userId", user.getId());
        left.put("current", remaining.size());
        left.put("max", CallRoomRegistry.MAX_PARTICIPANTS);
        sendToUsersExcept(remaining, user.getId(), left);
    }

    private void sendCallError(Long userId, Long chatRoomId, String callId, String error, int current) {
        ObjectNode rejected = objectMapper.createObjectNode();
        rejected.put("type", "call");
        rejected.put("action", "error");
        rejected.put("chatRoomId", chatRoomId);
        if (callId != null && !callId.isBlank()) {
            rejected.put("callId", callId);
        }
        rejected.put("error", error);
        rejected.put("current", current);
        rejected.put("max", CallRoomRegistry.MAX_PARTICIPANTS);
        sendToUser(userId, rejected);
    }

    public void broadcastMessage(Message saved) {
        broadcastMessage(saved, null);
    }

    public void broadcastMessageExcept(Message saved, Long exceptUserId) {
        broadcastMessage(saved, exceptUserId);
    }

    public void broadcastReadReceipt(Long chatRoomId, Long userId, Long lastReadMessageId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "read_receipt");
        envelope.put("chatRoomId", chatRoomId);
        envelope.put("userId", userId);
        if (lastReadMessageId != null) {
            envelope.put("lastReadMessageId", lastReadMessageId);
        }
        broadcastToRoomExcept(chatRoomId, userId, envelope);
    }

    public void broadcastReactionChanged(Long chatRoomId,
                                         Long messageId,
                                         String emoji,
                                         List<MessageDto.ReactionInfo> reactions,
                                         Long userId,
                                         boolean added) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "reaction_changed");
        envelope.put("chatRoomId", chatRoomId);
        envelope.put("messageId", messageId);
        envelope.put("emoji", emoji);
        envelope.put(added ? "addedByUserId" : "removedByUserId", userId);
        envelope.set("reactions", objectMapper.valueToTree(reactions));
        broadcastToRoom(chatRoomId, envelope);
    }

    public void broadcastPollVoted(Long chatRoomId, PollDto poll) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "poll_voted");
        envelope.put("chatRoomId", chatRoomId);
        envelope.put("pollId", poll.getId());
        envelope.set("poll", objectMapper.valueToTree(poll));
        broadcastToRoom(chatRoomId, envelope);
    }

    public void broadcastMessageAction(Long chatRoomId, String action, Object payload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "message_action");
        envelope.put("chatRoomId", chatRoomId);
        envelope.put("action", action);
        envelope.set("data", objectMapper.valueToTree(payload));
        broadcastToRoom(chatRoomId, envelope);
    }

    public void broadcastAppUpdate(AppVersion version) {
        if (version == null) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "app_update_available");
        if (version.getPlatform() != null) {
            envelope.put("platform", version.getPlatform().name());
        }
        envelope.put("versionName", fallback(version.getVersionName(), ""));
        if (version.getVersionCode() != null) {
            envelope.put("versionCode", version.getVersionCode());
        }
        envelope.put("forceUpdate", Boolean.TRUE.equals(version.getForceUpdate()));
        if (version.getReleaseNotes() != null) {
            envelope.put("releaseNotes", version.getReleaseNotes());
        }
        if (version.getDownloadUrl() != null) {
            envelope.put("downloadUrl", version.getDownloadUrl());
        }
        if (version.getFileSize() != null) {
            envelope.put("fileSize", version.getFileSize());
        }

        userSessions.forEach((userId, sessions) ->
                sessions.forEach(session -> sendJson(session, envelope)));
    }

    public boolean sendAgentToolRequest(Long userId, UUID callId, String toolName, JsonNode params) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "agent_tool_request");
        envelope.put("callId", callId.toString());
        envelope.put("toolName", toolName);
        envelope.set("params", params == null ? objectMapper.createObjectNode() : params);
        sessions.forEach(session -> sendJson(session, envelope));
        return true;
    }

    private void handleAgentToolResult(User user, JsonNode root) {
        String callIdText = root.path("callId").asText("");
        if (callIdText.isBlank()) {
            log.warn("agent_tool_result missing callId from userId={}", user.getId());
            return;
        }
        UUID callId;
        try {
            callId = UUID.fromString(callIdText);
        } catch (IllegalArgumentException e) {
            log.warn("agent_tool_result invalid callId={} from userId={}", callIdText, user.getId());
            return;
        }
        JsonNode result;
        if (root.has("error")) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("error", root.get("error"));
            result = wrapper;
        } else if (root.has("result")) {
            result = root.get("result");
        } else {
            ObjectNode wrapper = objectMapper.createObjectNode();
            ObjectNode error = wrapper.putObject("error");
            error.put("code", "client_tool_empty_result");
            error.put("message", "agent_tool_result did not include result or error");
            result = wrapper;
        }
        boolean accepted = pendingClientCallRegistry.complete(callId, user.getId(), result);
        if (accepted) {
            log.info("agent client tool result accepted callId={} userId={} resultBytes={}",
                    callId, user.getId(), result.toString().length());
        }
    }

    public void broadcastChatRoomUpdated(ChatRoom chatRoom) {
        if (chatRoom == null || chatRoom.getId() == null) {
            return;
        }
        ObjectNode room = objectMapper.createObjectNode();
        room.put("id", chatRoom.getId());
        room.put("name", fallback(chatRoom.getName(), ""));
        if (chatRoom.getDescription() != null) {
            room.put("description", chatRoom.getDescription());
        }
        if (chatRoom.getAnnouncement() != null) {
            room.put("announcement", chatRoom.getAnnouncement());
        }
        if (chatRoom.getAvatarUrl() != null) {
            room.put("avatarUrl", chatRoom.getAvatarUrl());
        }
        room.put("roomType", chatRoom.getRoomType() != null
                ? chatRoom.getRoomType().name()
                : "GROUP");
        room.put("anonymousEnabled", Boolean.TRUE.equals(chatRoom.getAnonymousEnabled()));
        if (chatRoom.getAnonymousTheme() != null) {
            room.put("anonymousTheme", chatRoom.getAnonymousTheme());
        }
        if (chatRoom.getCustomBackgroundPreset() != null) {
            room.put("customBackgroundPreset", chatRoom.getCustomBackgroundPreset());
        }
        if (chatRoom.getCustomBackgroundUrl() != null) {
            room.put("customBackgroundUrl", chatRoom.getCustomBackgroundUrl());
        }

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "room_updated");
        envelope.put("chatRoomId", chatRoom.getId());
        envelope.set("chatRoom", room);
        broadcastToRoom(chatRoom.getId(), envelope);
    }

    public boolean sendRoomDisplayStateChanged(Long userId, Long chatRoomId, Object state) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "room_display_state_changed");
        envelope.put("chatRoomId", chatRoomId);
        envelope.set("state", objectMapper.valueToTree(state));
        return sendToUser(userId, envelope);
    }

    private void broadcastMessage(Message saved, Long exceptUserId) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", "message");
        envelope.set("message", toMessageJson(saved));
        if (exceptUserId == null) {
            broadcastToRoom(saved.getChatRoom().getId(), envelope);
        } else {
            broadcastToRoomExcept(saved.getChatRoom().getId(), exceptUserId, envelope);
        }
        pushOfflineMessageNotification(saved);
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

    private boolean sendToUser(Long userId, Object payload) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null && !sessions.isEmpty()) {
            sessions.forEach(s -> sendJson(s, payload));
            return true;
        }
        return false;
    }

    private void sendToUsersExcept(Iterable<Long> userIds, Long exceptUserId, Object payload) {
        userIds.forEach(userId -> {
            if (userId.equals(exceptUserId)) return;
            sendToUser(userId, payload);
        });
    }

    private Iterable<Long> roomMembers(Long chatRoomId) {
        List<Long> ids = chatRoomRepository.findMemberUserIdsByRoomId(chatRoomId);
        return ids != null ? ids : List.of();
    }

    private void pushOfflineMessageNotification(Message message) {
        if (message == null
                || message.getChatRoom() == null
                || message.getSender() == null
                || Boolean.TRUE.equals(message.getIsDeleted())) {
            return;
        }

        Long chatRoomId = message.getChatRoom().getId();
        Long senderId = message.getSender().getId();
        String senderName = message.getSender().getDisplayName() != null
                && !message.getSender().getDisplayName().isBlank()
                ? message.getSender().getDisplayName()
                : message.getSender().getUsername();
        String title = message.getChatRoom().getName() != null
                && !message.getChatRoom().getName().isBlank()
                ? message.getChatRoom().getName()
                : senderName;
        String body = notificationBody(message);
        String data = notificationData(message);

        roomMembers(chatRoomId).forEach(userId -> {
            if (userId.equals(senderId) || userSessions.containsKey(userId)) {
                return;
            }
            boolean mentioned = message.getMentionedUserIds() != null
                    && message.getMentionedUserIds().contains(userId);
            // Item 5: push suppression follows the user's OWN notification mute, not the
            // moderation send-block. (Pre-split this read is_muted; after the split a
            // self-muted user has is_muted=0, so reading is_muted here would wrongly
            // resume their push notifications — must read is_notification_muted.)
            var member = chatRoomRepository.findMember(chatRoomId, userId);
            if (member.map(value -> Boolean.TRUE.equals(value.getIsBlocked())).orElse(false)) {
                return;
            }
            boolean muted = member
                    .map(value -> Boolean.TRUE.equals(value.getIsNotificationMuted()))
                    .orElse(false);
            if (muted && !mentioned) {
                return;
            }
            pushNotificationService.sendPushNotification(userId, title, body, data);
        });
    }

    private void pushOfflineCallInvitation(Long toUserId, User fromUser, Long chatRoomId,
                                           String callId, String mediaType) {
        if (toUserId == null || fromUser == null || chatRoomId == null) {
            return;
        }
        String fromName = fallback(fromUser.getDisplayName(), fromUser.getUsername());
        String title = "PM chat 来电";
        String body = fromName + " 来电";
        String data = offlineCallNotificationData(fromUser, chatRoomId, callId, mediaType);
        pushNotificationService.sendPushNotification(toUserId, title, body, data);
    }

    private String offlineCallNotificationData(User fromUser, Long chatRoomId,
                                               String callId, String mediaType) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "call",
                    "action", "invite",
                    "chatRoomId", chatRoomId,
                    "callId", fallback(callId, ""),
                    "fromUserId", fromUser.getId(),
                    "fromName", fallback(fromUser.getDisplayName(), fromUser.getUsername()),
                    "mediaType", fallback(mediaType, "AUDIO")
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String notificationBody(Message message) {
        Message.MessageType type = message.getMessageType();
        if (type == Message.MessageType.IMAGE) {
            return "[图片] " + fallback(message.getFileName(), message.getContent());
        }
        if (type == Message.MessageType.FILE) {
            return "[文件] " + fallback(message.getFileName(), message.getContent());
        }
        return fallback(message.getContent(), "[新消息]");
    }

    private String notificationData(Message message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "message",
                    "chatRoomId", message.getChatRoom().getId(),
                    "messageId", message.getId(),
                    "senderId", message.getSender().getId()
            ));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String fallback(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private void copyText(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.put(field, value.asText());
        }
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
        if (m.getBotConfig() != null) {
            json.put("botConfigId", m.getBotConfig().getId());
            json.put("botSenderId", m.getBotConfig().getId());
            json.put("botName", fallback(m.getBotDisplayName(), m.getBotConfig().getBotName()));
            if (m.getBotConfig().getBotAvatar() != null) {
                json.put("botAvatar", m.getBotConfig().getBotAvatar());
            }
        }
        boolean anonymous = Boolean.TRUE.equals(m.getIsAnonymous());
        json.put("isAnonymous", anonymous);
        if (anonymous && m.getAnonymousIdentity() != null) {
            json.put("anonymousIdentityId", m.getAnonymousIdentity().getId());
            json.put("anonymousName", m.getAnonymousIdentity().getAnonymousName());
            if (m.getAnonymousIdentity().getAnonymousAvatar() != null) {
                json.put("anonymousAvatar", m.getAnonymousIdentity().getAnonymousAvatar());
            }
            json.put("senderName", m.getAnonymousIdentity().getAnonymousName());
            if (m.getAnonymousIdentity().getAnonymousAvatar() != null) {
                json.put("senderAvatar", m.getAnonymousIdentity().getAnonymousAvatar());
            }
        }
        json.put("type", m.getMessageType() != null ? m.getMessageType().name() : "TEXT");
        if (m.getContentFormat() != null) {
            json.put("contentFormat", m.getContentFormat().name());
        }
        json.put("status", m.getMessageStatus() != null ? m.getMessageStatus().name() : "SENT");
        if (m.getCreatedAt() != null) {
            json.put("timestamp", m.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            json.put("createdAt", m.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (m.getUpdatedAt() != null) {
            json.put("updatedAt", m.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (m.getReplyToMessage() != null) {
            json.put("replyToMessageId", m.getReplyToMessage().getId());
        }
        if (m.getMentionedUserIds() != null && !m.getMentionedUserIds().isEmpty()) {
            json.set("mentionedUserIds", objectMapper.valueToTree(m.getMentionedUserIds()));
        }
        if (m.getFileUrl() != null) json.put("fileUrl", m.getFileUrl());
        if (m.getFileName() != null) json.put("fileName", m.getFileName());
        if (m.getFileSize() != null) json.put("fileSize", m.getFileSize());
        if (m.getFileType() != null) json.put("fileType", m.getFileType());
        if (m.getImageGenPrompt() != null) json.put("imageGenPrompt", m.getImageGenPrompt());
        if (m.getImageGenStatus() != null) json.put("imageGenStatus", m.getImageGenStatus().name());
        if (m.getImageGenUrl() != null) json.put("imageGenUrl", m.getImageGenUrl());
        if (m.getImageGenProviderTaskId() != null) {
            json.put("imageGenProviderTaskId", m.getImageGenProviderTaskId());
        }
        if (m.getEncryptedContent() != null && m.getEncryptedContent().length > 0) {
            json.put("encryptedContent", java.util.Base64.getEncoder().encodeToString(m.getEncryptedContent()));
        }
        if (m.getEncryptionVersion() != null) {
            json.put("encryptionVersion", m.getEncryptionVersion());
        }
        json.put("isDeleted", Boolean.TRUE.equals(m.getIsDeleted()));
        json.put("isEdited", Boolean.TRUE.equals(m.getIsEdited()));
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
