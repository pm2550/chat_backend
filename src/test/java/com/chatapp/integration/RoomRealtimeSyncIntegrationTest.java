package com.chatapp.integration;

import com.chatapp.controller.ChatRoomController;
import com.chatapp.controller.MessageController;
import com.chatapp.controller.RoomPinController;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.AnonymousService;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.MessageService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserProfileService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 会话状态的实时同步：房间资料、成员变化、已读、置顶/收藏、输入状态、在线状态。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "spring.main.allow-circular-references=true",
                "spring.main.allow-bean-definition-overriding=true",
                "server.servlet.context-path="
        }
)
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Room realtime sync")
class RoomRealtimeSyncIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserProfileService userProfileService;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private MessageService messageService;
    @Autowired private AnonymousService anonymousService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RawWebSocketHandler rawWebSocketHandler;
    @Autowired private MessageController messageController;
    @Autowired private ChatRoomController chatRoomController;
    @Autowired private RoomPinController roomPinController;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private final List<RecordingSession> openSessions = new ArrayList<>();

    private User alice;
    private User bob;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        alice = registerUser("alice", "Alice");
        bob = registerUser("bob", "Bob");
        room = chatRoomService.createGroupChat(
                alice.getId(), "sync-room-" + UUID.randomUUID(), "sync", List.of(bob.getId()));
    }

    @AfterEach
    void tearDown() {
        for (RecordingSession session : openSessions) {
            rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
        openSessions.clear();
    }

    @Test
    @DisplayName("Starring a message syncs only to the starring user's own devices")
    void star_is_sent_only_to_own_sessions() throws Exception {
        Message message = messageService.sendMessage(bob.getId(), room.getId(), "star me", Message.MessageType.TEXT);
        RecordingSession alicePhone = connect(alice);
        RecordingSession aliceDesktop = connect(alice);
        RecordingSession bobSession = connect(bob);
        drain(alicePhone, aliceDesktop, bobSession);

        messageController.starMessage(message.getId(), auth(alice));

        JsonNode desktop = await(aliceDesktop, "message_action");
        assertNotNull(desktop, "alice's other device should learn about the star");
        assertEquals("star_added", desktop.path("action").asText());
        assertEquals(message.getId().longValue(), desktop.path("data").path("messageId").asLong());
        assertNotNull(await(alicePhone, "message_action"));
        assertNull(await(bobSession, "message_action", 300), "stars must not leak to other members");

        messageController.unstarMessage(message.getId(), auth(alice));
        assertEquals("star_removed", await(aliceDesktop, "message_action").path("action").asText());
        assertNull(await(bobSession, "message_action", 300));
    }

    @Test
    @DisplayName("Pin events still reach every member with the fresh pin list")
    void pin_is_broadcast_to_room() throws Exception {
        Message message = messageService.sendMessage(bob.getId(), room.getId(), "pin me", Message.MessageType.TEXT);
        RecordingSession bobSession = connect(bob);
        drain(bobSession);

        roomPinController.pinMessage(room.getId(), message.getId(), auth(alice));

        JsonNode pin = await(bobSession, "message_action");
        assertNotNull(pin);
        assertEquals("pin_added", pin.path("action").asText());
        assertEquals(message.getId().longValue(), pin.path("data").path("pins").get(0).path("id").asLong());
    }

    @Test
    @DisplayName("Reading a room clears the unread count on the reader's other devices")
    void read_all_syncs_to_own_other_sessions() throws Exception {
        messageService.sendMessage(alice.getId(), room.getId(), "unread", Message.MessageType.TEXT);
        RecordingSession aliceSession = connect(alice);
        RecordingSession bobPhone = connect(bob);
        RecordingSession bobDesktop = connect(bob);
        drain(aliceSession, bobPhone, bobDesktop);

        rawWebSocketHandler.handleMessage(bobPhone, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "read",
                "chatRoomId", room.getId()))));

        JsonNode own = await(bobDesktop, "read_receipt");
        assertNotNull(own, "bob's desktop never learned the room was read on his phone");
        assertEquals(bob.getId().longValue(), own.path("userId").asLong());
        assertEquals(0, own.path("unreadCount").asInt(-1));

        JsonNode peer = await(aliceSession, "read_receipt");
        assertNotNull(peer);
        assertTrue(peer.path("lastReadMessageId").isNumber());
        assertFalse(peer.has("unreadCount"), "a member's unread count is private to them");
    }

    @Test
    @DisplayName("REST single-message read emits a read receipt once")
    void rest_single_message_read_emits_receipt_once() throws Exception {
        Message message = messageService.sendMessage(alice.getId(), room.getId(), "read me", Message.MessageType.TEXT);
        RecordingSession aliceSession = connect(alice);
        RecordingSession bobDesktop = connect(bob);
        drain(aliceSession, bobDesktop);

        messageController.markMessageAsRead(message.getId(), auth(bob));

        JsonNode receipt = await(aliceSession, "read_receipt");
        assertNotNull(receipt, "sender should see the read tick without reloading");
        assertEquals(message.getId().longValue(), receipt.path("messageId").asLong());
        assertEquals(bob.getId().longValue(), receipt.path("userId").asLong());
        assertEquals(0, await(bobDesktop, "read_receipt").path("unreadCount").asInt(-1));

        messageController.markMessageAsRead(message.getId(), auth(bob));
        assertNull(await(aliceSession, "read_receipt", 300), "re-reading must not count again");
    }

    @Test
    @DisplayName("Room info changes broadcast room_updated to every member")
    void room_changes_broadcast_room_updated() throws Exception {
        RecordingSession bobSession = connect(bob);
        drain(bobSession);

        chatRoomController.patchChatRoom(room.getId(),
                Map.of("name", "renamed", "announcement", "new notice"), auth(alice));
        JsonNode renamed = await(bobSession, "room_updated");
        assertNotNull(renamed, "rename should reach other members");
        assertEquals("renamed", renamed.path("chatRoom").path("name").asText());
        assertEquals("new notice", renamed.path("chatRoom").path("announcement").asText());
        assertEquals(2, renamed.path("chatRoom").path("memberCount").asInt());

        anonymousService.toggleAnonymous(room.getId(), alice.getId(), true);
        JsonNode anonymous = await(bobSession, "room_updated");
        assertNotNull(anonymous, "anonymous toggle should reach other members");
        assertTrue(anonymous.path("chatRoom").path("anonymousEnabled").asBoolean());

        chatRoomService.updateRoomBackgroundPreset(room.getId(), alice.getId(), "pixel_mint");
        JsonNode background = await(bobSession, "room_updated");
        assertNotNull(background, "background change should reach other members");
        assertEquals("pixel_mint", background.path("chatRoom").path("customBackgroundPreset").asText());
    }

    @Test
    @DisplayName("Kicked member is told to drop the room; the rest get the new member count")
    void kick_notifies_removed_member_and_room() throws Exception {
        RecordingSession aliceSession = connect(alice);
        RecordingSession bobSession = connect(bob);
        drain(aliceSession, bobSession);

        chatRoomService.kickMember(room.getId(), alice.getId(), bob.getId());

        JsonNode removed = await(bobSession, "room_membership_removed");
        assertNotNull(removed, "kicked member was not told");
        assertEquals(room.getId().longValue(), removed.path("chatRoomId").asLong());
        assertEquals("kicked", removed.path("reason").asText());
        assertNull(await(bobSession, "room_updated", 300), "a removed member gets no room broadcasts");

        JsonNode updated = await(aliceSession, "room_updated");
        assertNotNull(updated);
        assertEquals(1, updated.path("chatRoom").path("memberCount").asInt());
    }

    @Test
    @DisplayName("Leaving a room removes it from the user's other devices")
    void leave_notifies_own_sessions() throws Exception {
        RecordingSession bobDesktop = connect(bob);
        drain(bobDesktop);

        chatRoomService.leaveChatRoom(room.getId(), bob.getId());

        JsonNode removed = await(bobDesktop, "room_membership_removed");
        assertNotNull(removed);
        assertEquals("left", removed.path("reason").asText());
    }

    @Test
    @DisplayName("An invited member is told a room was added")
    void add_member_notifies_new_member() throws Exception {
        User carol = registerUser("carol", "Carol");
        RecordingSession carolSession = connect(carol);
        RecordingSession bobSession = connect(bob);
        drain(carolSession, bobSession);

        chatRoomService.addMember(room.getId(), alice.getId(), carol.getId());

        JsonNode added = await(carolSession, "room_membership_added");
        assertNotNull(added, "new member's room list never learns about the room");
        assertEquals(room.getId().longValue(), added.path("chatRoomId").asLong());
        assertEquals(3, await(bobSession, "room_updated").path("chatRoom").path("memberCount").asInt());
    }

    @Test
    @DisplayName("Pin/mute changes on one device sync to the user's other devices")
    void notification_settings_sync_to_own_sessions() throws Exception {
        RecordingSession bobDesktop = connect(bob);
        drain(bobDesktop);

        ChatRoomController.NotificationSettingsRequest request = new ChatRoomController.NotificationSettingsRequest();
        request.setPinned(true);
        request.setMuted(true);
        chatRoomController.updateNotificationSettings(room.getId(), request, auth(bob));

        JsonNode state = await(bobDesktop, "room_display_state_changed");
        assertNotNull(state, "pinning a room did not sync to the other device");
        assertTrue(state.path("state").path("pinned").asBoolean());
        assertTrue(state.path("state").path("muted").asBoolean());
    }

    @Test
    @DisplayName("Typing is broadcast once, as typing_aggregated only")
    void typing_is_not_duplicated() throws Exception {
        RecordingSession aliceSession = connect(alice);
        RecordingSession bobSession = connect(bob);
        drain(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "typing",
                "chatRoomId", room.getId(),
                "isTyping", true))));
        rawWebSocketHandler.flushTypingAggregates();

        List<String> types = new ArrayList<>();
        String raw;
        while ((raw = bobSession.messages.poll(500, TimeUnit.MILLISECONDS)) != null) {
            types.add(objectMapper.readTree(raw).path("type").asText());
        }
        assertEquals(1, types.stream().filter("typing_aggregated"::equals).count());
        assertFalse(types.contains("typing"), "legacy duplicate typing frame is still sent: " + types);
    }

    @Test
    @DisplayName("Connecting shows the status the user chose, and changing it is broadcast")
    void chosen_presence_is_broadcast() throws Exception {
        userProfileService.updateOnlineStatus(bob.getId(), User.OnlineStatus.AWAY);
        bob = userRepository.findById(bob.getId()).orElseThrow();
        RecordingSession aliceSession = connect(alice);
        drain(aliceSession);

        RecordingSession bobSession = connect(bob);
        JsonNode connected = await(aliceSession, "status");
        assertNotNull(connected);
        assertEquals(bob.getId().longValue(), connected.path("userId").asLong());
        assertEquals("AWAY", connected.path("onlineStatus").asText());
        drain(bobSession);

        User reloaded = userRepository.findById(bob.getId()).orElseThrow();
        assertEquals(User.OnlineStatus.AWAY, reloaded.chosenPresence());
        rawWebSocketHandler.broadcastPresenceChanged(bob.getId(), User.OnlineStatus.BUSY);
        assertEquals("BUSY", await(aliceSession, "status").path("onlineStatus").asText());
    }

    private Authentication auth(User user) {
        return new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of());
    }

    private User registerUser(String prefix, String displayName) {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setEmail(username + "@test.com");
        request.setDisplayName(displayName);
        UserDto dto = userService.registerUser(request);
        return userRepository.findById(dto.getId()).orElseThrow();
    }

    private RecordingSession connect(User user) {
        RecordingSession session = new RecordingSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, user);
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
        return session;
    }

    private void drain(RecordingSession... sessions) {
        for (RecordingSession session : sessions) {
            session.messages.clear();
        }
    }

    private JsonNode await(RecordingSession session, String type) throws Exception {
        return await(session, type, 3000);
    }

    private JsonNode await(RecordingSession session, String type, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String raw = session.messages.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (raw == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        return null;
    }

    static class RecordingSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final Map<String, Object> attributes = new HashMap<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override public String getId() { return id; }
        @Override public URI getUri() { return URI.create("ws://localhost/api/ws"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getTextMessageSizeLimit() { return 64 * 1024; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { }
        @Override public int getBinaryMessageSizeLimit() { return 64 * 1024; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }
        @Override public void sendMessage(WebSocketMessage<?> message) { messages.add(message.getPayload().toString()); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
        @Override public void close(CloseStatus status) { }
    }
}
