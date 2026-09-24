package com.chatapp.integration;

import com.chatapp.controller.PollController;
import com.chatapp.dto.MessageDto;
import com.chatapp.dto.PollDto;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.dto.AppVersionDto;
import com.chatapp.entity.DeviceToken;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.E2eeKeyService;
import com.chatapp.service.LLMService;
import com.chatapp.service.MessageReactionService;
import com.chatapp.service.MessageService;
import com.chatapp.service.AnonymousService;
import com.chatapp.service.AppVersionService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import com.chatapp.websocket.JwtHandshakeInterceptor;
import com.chatapp.websocket.CallRoomRegistry;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@DisplayName("Raw WebSocket Integration Test")
class RawWebSocketIntegrationTest {

    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserService userService;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private MessageService messageService;
    @Autowired private AnonymousService anonymousService;
    @Autowired private AppVersionService appVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RawWebSocketHandler rawWebSocketHandler;
    @Autowired private CallRoomRegistry callRoomRegistry;
    @Autowired private JwtHandshakeInterceptor jwtHandshakeInterceptor;
    @Autowired private MessageReactionService messageReactionService;
    @Autowired private PollController pollController;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private final List<TestWebSocketSession> openSessions = new ArrayList<>();

    private User alice;
    private User bob;
    private String aliceToken;
    private ChatRoom room;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        callRoomRegistry.clear();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto.RegisterRequest aliceReq = new UserDto.RegisterRequest();
        aliceReq.setUsername("alice_" + suffix);
        aliceReq.setPassword("password123");
        aliceReq.setEmail("alice_" + suffix + "@test.com");
        aliceReq.setDisplayName("Alice");
        UserDto aliceDto = userService.registerUser(aliceReq);
        alice = userRepository.findById(aliceDto.getId()).orElseThrow();

        UserDto.RegisterRequest bobReq = new UserDto.RegisterRequest();
        bobReq.setUsername("bob_" + suffix);
        bobReq.setPassword("password123");
        bobReq.setEmail("bob_" + suffix + "@test.com");
        bobReq.setDisplayName("Bob");
        UserDto bobDto = userService.registerUser(bobReq);
        bob = userRepository.findById(bobDto.getId()).orElseThrow();

        aliceToken = jwtUtils.generateAccessToken(alice.getUsername());

        room = chatRoomService.createGroupChat(
                alice.getId(), "test-room-" + suffix, "ws-test",
                List.of(bob.getId()));
    }

    @AfterEach
    void tearDown() {
        for (TestWebSocketSession session : openSessions) {
            rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
            session.markClosed(CloseStatus.NORMAL);
        }
        openSessions.clear();
        callRoomRegistry.clear();
    }

    @Test
    @DisplayName("Handshake rejected without token")
    void handshake_requires_token() {
        Map<String, Object> attrs = new HashMap<>();
        boolean accepted = jwtHandshakeInterceptor.beforeHandshake(
                request("ws://localhost/api/ws"),
                null,
                rawWebSocketHandler,
                attrs
        );

        assertFalse(accepted);
        assertTrue(attrs.isEmpty());
    }

    @Test
    @DisplayName("Handshake rejected with malformed token")
    void handshake_rejects_malformed_token() {
        Map<String, Object> attrs = new HashMap<>();
        boolean accepted = jwtHandshakeInterceptor.beforeHandshake(
                request("ws://localhost/api/ws?token=not-a-real-jwt"),
                null,
                rawWebSocketHandler,
                attrs
        );

        assertFalse(accepted);
        assertTrue(attrs.isEmpty());
    }

    @Test
    @DisplayName("Handshake rejected when token is blacklisted")
    void handshake_rejects_blacklisted_token() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        Map<String, Object> attrs = new HashMap<>();
        boolean accepted = jwtHandshakeInterceptor.beforeHandshake(
                request("ws://localhost/api/ws?token=" + aliceToken),
                null,
                rawWebSocketHandler,
                attrs
        );

        assertFalse(accepted);
        assertTrue(attrs.isEmpty());
    }

    @Test
    @DisplayName("Three-party broadcast delivers single message to all room members")
    void three_party_broadcast() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto.RegisterRequest carolReq = new UserDto.RegisterRequest();
        carolReq.setUsername("carol_" + suffix);
        carolReq.setPassword("password123");
        carolReq.setEmail("carol_" + suffix + "@test.com");
        carolReq.setDisplayName("Carol");
        UserDto carolDto = userService.registerUser(carolReq);
        User carol = userRepository.findById(carolDto.getId()).orElseThrow();

        ChatRoom bigRoom = chatRoomService.createGroupChat(
                alice.getId(), "trio-" + suffix, "trio",
                List.of(bob.getId(), carol.getId()));

        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        TestWebSocketSession carolSession = connect(carol);
        drainStatus(aliceSession, bobSession, carolSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", bigRoom.getId(),
                "content", "ping all",
                "messageType", "TEXT"
        ))));

        JsonNode bobMsg = awaitMessage(bobSession, "message");
        JsonNode carolMsg = awaitMessage(carolSession, "message");
        assertNotNull(bobMsg, "bob missed broadcast");
        assertNotNull(carolMsg, "carol missed broadcast");
        assertEquals("ping all", bobMsg.path("message").path("content").asText());
        assertEquals("ping all", carolMsg.path("message").path("content").asText());
    }

    @Test
    @DisplayName("Anonymous WebSocket message broadcasts anonymous metadata")
    void anonymous_ws_message_broadcasts_metadata() throws Exception {
        anonymousService.toggleAnonymous(room.getId(), alice.getId(), true);

        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "masked hello",
                "messageType", "TEXT",
                "isAnonymous", true
        ))));

        JsonNode bobMsg = awaitMessage(bobSession, "message");
        assertNotNull(bobMsg, "bob missed anonymous broadcast");
        assertEquals("masked hello", bobMsg.path("message").path("content").asText());
        assertTrue(bobMsg.path("message").path("isAnonymous").asBoolean());
        assertFalse(bobMsg.path("message").path("anonymousName").asText().isBlank());
    }

    @Test
    @DisplayName("Anonymous WS broadcast: others get no real identity, the sender's sessions get sentByMe")
    void anonymous_ws_broadcast_is_personalised_per_recipient() throws Exception {
        anonymousService.toggleAnonymous(room.getId(), alice.getId(), true);

        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession aliceSecondDevice = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, aliceSecondDevice, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "who am i",
                "messageType", "TEXT",
                "isAnonymous", true
        ))));

        String bobRaw = awaitRawMessage(bobSession, "message");
        assertNotNull(bobRaw, "bob missed anonymous broadcast");
        assertNoIdentityOf(alice, bobRaw);
        JsonNode bobMsg = objectMapper.readTree(bobRaw).path("message");
        assertTrue(bobMsg.path("senderId").isNull(), "anonymous senderId must not reach others");
        assertTrue(bobMsg.path("sender").isNull(), "anonymous sender object must not reach others");
        assertFalse(bobMsg.path("sentByMe").asBoolean(true));
        String anonymousName = bobMsg.path("anonymousName").asText();
        assertFalse(anonymousName.isBlank());
        assertEquals(anonymousName, bobMsg.path("senderName").asText());

        for (TestWebSocketSession own : List.of(aliceSession, aliceSecondDevice)) {
            JsonNode ownMsg = awaitMessage(own, "message");
            assertNotNull(ownMsg, "sender's own sessions must get the message back");
            assertTrue(ownMsg.path("message").path("sentByMe").asBoolean(), "own sessions get sentByMe=true");
            assertEquals(alice.getId().longValue(), ownMsg.path("message").path("senderId").asLong());
            assertEquals(anonymousName, ownMsg.path("message").path("senderName").asText());
        }

        // 编辑后整条替换的推送同样按人区分。
        Long messageId = bobMsg.path("id").asLong();
        Message edited = messageService.editMessage(messageId, alice.getId(), "who am i (edited)");
        rawWebSocketHandler.broadcastMessageUpdated(edited);
        String bobUpdate = awaitRawMessage(bobSession, "message");
        assertNotNull(bobUpdate);
        assertNoIdentityOf(alice, bobUpdate);
        JsonNode aliceUpdate = awaitMessage(aliceSession, "message");
        assertTrue(aliceUpdate.path("message").path("sentByMe").asBoolean());
    }

    @Test
    @DisplayName("Offline push for an anonymous message carries no real name or sender id")
    void anonymous_message_push_has_no_real_identity() throws Exception {
        anonymousService.toggleAnonymous(room.getId(), alice.getId(), true);
        TestWebSocketSession aliceSession = connect(alice);
        drainStatus(aliceSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "offline anonymous ping",
                "messageType", "TEXT",
                "isAnonymous", true
        ))));
        assertNotNull(awaitMessage(aliceSession, "message"));

        org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> data = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pushNotificationService).sendPushNotification(
                eq(bob.getId()), title.capture(), body.capture(), data.capture());
        String everything = title.getValue() + "\n" + body.getValue() + "\n" + data.getValue();
        assertNoIdentityOf(alice, everything);
        assertFalse(data.getValue().contains("senderId"), "push data must not carry the anonymous sender id");
        assertEquals("offline anonymous ping", body.getValue());
    }

    @Test
    @DisplayName("Replying to an anonymous message does not leak the quoted sender")
    void reply_to_anonymous_message_does_not_leak_quoted_sender() throws Exception {
        anonymousService.toggleAnonymous(room.getId(), alice.getId(), true);
        Message anonymous = messageService.sendAnonymousEncryptedMessage(
                alice.getId(), room.getId(), "secret take", null, null, Message.MessageType.TEXT, null);
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "replying to the masked one",
                "messageType", "TEXT",
                "replyToId", anonymous.getId()
        ))));

        String bobRaw = awaitRawMessage(bobSession, "message");
        assertNotNull(bobRaw);
        assertNoIdentityOf(alice, bobRaw);
        JsonNode quoted = objectMapper.readTree(bobRaw).path("message").path("replyToMessage");
        assertEquals("secret take", quoted.path("content").asText());
        assertTrue(quoted.path("senderId").isNull());
        assertFalse(quoted.path("anonymousName").asText().isBlank());

        JsonNode aliceView = awaitMessage(aliceSession, "message");
        assertNotNull(aliceView);
        // alice 看到的是别人的回复（不是她发的），但被引用的那条是她自己的匿名消息，也不带她的身份。
        assertFalse(aliceView.path("message").path("sentByMe").asBoolean(true));
        assertTrue(aliceView.path("message").path("replyToMessage").path("senderId").isNull());
    }

    private void assertNoIdentityOf(User user, String payload) {
        assertFalse(payload.contains(user.getUsername()), "payload leaks username: " + payload);
        assertFalse(payload.contains(user.getEmail()), "payload leaks email: " + payload);
        assertFalse(payload.contains("\"" + user.getDisplayName() + "\""), "payload leaks display name: " + payload);
        assertFalse(payload.contains("\"senderId\":" + user.getId()), "payload leaks sender id: " + payload);
    }

    private String awaitRawMessage(TestWebSocketSession session, String expectedType) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String msg = session.messages.poll(3000, TimeUnit.MILLISECONDS);
            if (msg == null) return null;
            if (expectedType.equals(objectMapper.readTree(msg).path("type").asText())) {
                return msg;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Sending message to a room user is not a member of is rejected")
    void send_requires_membership() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ChatRoom isolated = chatRoomService.createGroupChat(
                alice.getId(), "isolated-" + suffix, "isolated", List.of());

        TestWebSocketSession bobSession = connect(bob);
        drainStatus(bobSession);

        rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", isolated.getId(),
                "content", "intruder",
                "messageType", "TEXT"
        ))));

        JsonNode err = awaitMessage(bobSession, "error");
        assertNotNull(err, "bob should receive error frame when sending to a room he isn't in");
    }

    @Test
    @DisplayName("Alice sends message, Bob receives broadcast")
    void broadcast_roundtrip() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "hello from alice",
                "messageType", "TEXT"
        ))));

        JsonNode received = awaitMessage(bobSession, "message");
        assertNotNull(received, "bob did not receive broadcast within timeout");
        assertEquals("hello from alice", received.path("message").path("content").asText());
        assertEquals(alice.getId().longValue(), received.path("message").path("senderId").asLong());
    }

    @Test
    @DisplayName("Ping receives pong")
    void ping_pong() throws Exception {
        TestWebSocketSession session = connect(alice);

        rawWebSocketHandler.handleMessage(session, new TextMessage("{\"type\":\"ping\"}"));

        JsonNode pong = awaitMessage(session, "pong");
        assertNotNull(pong, "expected pong");
    }

    @Test
    @DisplayName("Typing event is aggregated and broadcast to room members")
    void typing_broadcast() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "typing",
                "chatRoomId", room.getId(),
                "isTyping", true
        ))));

        JsonNode received = awaitMessage(bobSession, "typing_aggregated");
        assertNotNull(received, "bob did not receive aggregated typing event");
        assertEquals(room.getId().longValue(), received.path("chatRoomId").asLong());
        assertTrue(received.path("userIds").isArray());
        assertEquals(alice.getId().longValue(), received.path("userIds").get(0).asLong());
        assertEquals(alice.getDisplayName(), received.path("userNames").get(0).asText());
    }

    @Test
    @DisplayName("Call signal is relayed to room members with sender metadata")
    void call_signal_broadcast() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "call",
                "action", "invite",
                "chatRoomId", room.getId(),
                "callId", "call-test-1",
                "mediaType", "VIDEO"
        ))));

        JsonNode received = awaitMessage(bobSession, "call");
        assertNotNull(received, "bob did not receive call signal");
        assertEquals("invite", received.path("action").asText());
        assertEquals(room.getId().longValue(), received.path("chatRoomId").asLong());
        assertEquals(alice.getId().longValue(), received.path("fromUserId").asLong());
        assertEquals("Alice", received.path("fromName").asText());
        assertEquals("call-test-1", received.path("callId").asText());
        assertEquals("VIDEO", received.path("mediaType").asText());
        assertTrue(aliceSession.messages.isEmpty(), "sender should not receive its own call signal");
    }

    @Test
    @DisplayName("Mesh call join broadcasts participant_joined to existing participants")
    void mesh_call_join_broadcasts_participant_joined() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        sendCall(aliceSession, Map.of(
                "action", "join",
                "chatRoomId", room.getId(),
                "callId", "mesh-join-1",
                "mediaType", "VIDEO"
        ));
        JsonNode aliceAccepted = awaitCallAction(aliceSession, "join_accepted");
        assertNotNull(aliceAccepted, "alice should receive join_accepted");
        assertEquals(1, aliceAccepted.path("current").asInt());
        assertEquals(0, aliceAccepted.path("existingParticipantIds").size());

        sendCall(bobSession, Map.of(
                "action", "join",
                "chatRoomId", room.getId(),
                "callId", "mesh-join-1",
                "mediaType", "VIDEO"
        ));

        JsonNode bobAccepted = awaitCallAction(bobSession, "join_accepted");
        JsonNode aliceJoined = awaitCallAction(aliceSession, "participant_joined");
        assertNotNull(bobAccepted, "bob should receive join_accepted");
        assertNotNull(aliceJoined, "alice should receive participant_joined");
        assertEquals(2, bobAccepted.path("current").asInt());
        assertEquals(alice.getId().longValue(), bobAccepted.path("existingParticipantIds").get(0).asLong());
        assertEquals(bob.getId().longValue(), aliceJoined.path("userId").asLong());
        assertEquals("Bob", aliceJoined.path("name").asText());
        assertEquals(2, callRoomRegistry.getParticipants("mesh-join-1").size());
    }

    @Test
    @DisplayName("Mesh call join rejects seventh participant with ROOM_FULL")
    void mesh_call_join_rejects_seventh_participant() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            users.add(registerUser("mesh_user_" + i + "_" + suffix, "Mesh " + i));
        }
        ChatRoom meshRoom = chatRoomService.createGroupChat(
                users.get(0).getId(), "mesh-cap-" + suffix, "mesh cap",
                users.subList(1, users.size()).stream().map(User::getId).toList());
        List<TestWebSocketSession> sessions = users.stream().map(this::connect).toList();
        drainStatus(sessions.toArray(TestWebSocketSession[]::new));

        for (int i = 0; i < 6; i++) {
            sendCall(sessions.get(i), Map.of(
                    "action", "join",
                    "chatRoomId", meshRoom.getId(),
                    "callId", "mesh-cap-1",
                    "mediaType", "AUDIO"
            ));
            assertNotNull(awaitCallAction(sessions.get(i), "join_accepted"),
                    "participant " + i + " should join");
        }

        sendCall(sessions.get(6), Map.of(
                "action", "join",
                "chatRoomId", meshRoom.getId(),
                "callId", "mesh-cap-1",
                "mediaType", "AUDIO"
        ));

        JsonNode rejected = awaitCallAction(sessions.get(6), "error");
        assertNotNull(rejected, "seventh participant should receive ROOM_FULL");
        assertEquals("ROOM_FULL", rejected.path("error").asText());
        assertEquals(6, rejected.path("current").asInt());
        assertEquals(6, rejected.path("max").asInt());
        assertEquals(6, callRoomRegistry.getParticipants("mesh-cap-1").size());
    }

    @Test
    @DisplayName("Mesh call leave broadcasts participant_left and decrements registry")
    void mesh_call_leave_broadcasts_participant_left() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        sendCall(aliceSession, Map.of(
                "action", "join",
                "chatRoomId", room.getId(),
                "callId", "mesh-leave-1"
        ));
        assertNotNull(awaitCallAction(aliceSession, "join_accepted"));
        sendCall(bobSession, Map.of(
                "action", "join",
                "chatRoomId", room.getId(),
                "callId", "mesh-leave-1"
        ));
        assertNotNull(awaitCallAction(bobSession, "join_accepted"));
        assertNotNull(awaitCallAction(aliceSession, "participant_joined"));

        sendCall(bobSession, Map.of(
                "action", "leave",
                "chatRoomId", room.getId(),
                "callId", "mesh-leave-1"
        ));

        JsonNode left = awaitCallAction(aliceSession, "participant_left");
        assertNotNull(left, "alice should receive participant_left");
        assertEquals(bob.getId().longValue(), left.path("userId").asLong());
        assertEquals(1, left.path("current").asInt());
        assertEquals(List.of(alice.getId()), callRoomRegistry.getParticipants("mesh-leave-1"));
    }

    @Test
    @DisplayName("Legacy targeted 1v1 call signals are still relayed unchanged")
    void legacy_targeted_call_signal_regression() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        sendCall(aliceSession, Map.of(
                "action", "offer",
                "chatRoomId", room.getId(),
                "callId", "legacy-1v1",
                "toUserId", bob.getId(),
                "mediaType", "VIDEO",
                "sdpType", "offer",
                "sdp", "v=0"
        ));

        JsonNode received = awaitCallAction(bobSession, "offer");
        assertNotNull(received, "bob should receive targeted offer");
        assertEquals(alice.getId().longValue(), received.path("fromUserId").asLong());
        assertEquals(bob.getId().longValue(), received.path("toUserId").asLong());
        assertEquals("legacy-1v1", received.path("callId").asText());
        assertEquals("offer", received.path("sdpType").asText());
        assertEquals("v=0", received.path("sdp").asText());
        assertTrue(aliceSession.messages.isEmpty(), "targeted sender should not receive echo");
    }

    @Test
    @DisplayName("Targeted call invite acks ringing to caller when peer is online")
    void targeted_call_invite_sends_ringing_ack() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        sendCall(aliceSession, Map.of(
                "action", "invite",
                "chatRoomId", room.getId(),
                "callId", "targeted-ringing-1",
                "toUserId", bob.getId(),
                "mediaType", "AUDIO"
        ));

        JsonNode invite = awaitCallAction(bobSession, "invite");
        assertNotNull(invite, "bob should receive targeted invite");
        assertEquals(alice.getId().longValue(), invite.path("fromUserId").asLong());

        JsonNode ringing = awaitCallAction(aliceSession, "call_ringing");
        assertNotNull(ringing, "alice should receive ringing ack");
        assertEquals("targeted-ringing-1", ringing.path("callId").asText());
        assertEquals(bob.getId().longValue(), ringing.path("fromUserId").asLong());
        assertEquals(alice.getId().longValue(), ringing.path("toUserId").asLong());
    }

    @Test
    @DisplayName("Offline targeted call invite falls back to push notification")
    void offline_call_invite_pushes_notification() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        drainStatus(aliceSession);

        sendCall(aliceSession, Map.of(
                "action", "invite",
                "chatRoomId", room.getId(),
                "callId", "offline-call-1",
                "toUserId", bob.getId(),
                "mediaType", "AUDIO"
        ));

        verify(pushNotificationService).sendPushNotification(
                eq(bob.getId()),
                eq("PM chat 来电"),
                contains("Alice 来电"),
                argThat(data -> data.contains("\"type\":\"call\"")
                        && data.contains("\"action\":\"invite\"")
                        && data.contains("\"callId\":\"offline-call-1\"")
                        && data.contains("\"chatRoomId\":" + room.getId()))
        );
        assertNull(awaitCallAction(aliceSession, "call_ringing"),
                "offline callee should not produce ringing ack");
    }

    @Test
    @DisplayName("Call signal from non-member is not relayed")
    void call_signal_requires_membership() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ChatRoom isolated = chatRoomService.createGroupChat(
                alice.getId(), "call-isolated-" + suffix, "call-isolated", List.of());

        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "call",
                "action", "invite",
                "chatRoomId", isolated.getId(),
                "callId", "call-denied",
                "mediaType", "AUDIO"
        ))));

        assertNull(aliceSession.messages.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("Encrypted WebSocket message preserves ciphertext fields in a private chat")
    void encrypted_message_broadcast() throws Exception {
        ChatRoom dm = chatRoomService.createPrivateChat(alice.getId(), bob.getId());
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", dm.getId(),
                "content", "[加密消息]",
                "messageType", "TEXT",
                "encryptedContent", "ZW5jcnlwdGVk",
                "encryptionVersion", E2eeKeyService.MESSAGE_ENCRYPTION_VERSION
        ))));

        JsonNode received = awaitMessage(bobSession, "message");
        assertNotNull(received, "bob did not receive encrypted broadcast");
        assertEquals(E2eeKeyService.OLD_CLIENT_PLACEHOLDER, received.path("message").path("content").asText());
        assertEquals("ZW5jcnlwdGVk", received.path("message").path("encryptedContent").asText());
        assertEquals(E2eeKeyService.MESSAGE_ENCRYPTION_VERSION,
                received.path("message").path("encryptionVersion").asInt());
    }

    @Test
    @DisplayName("Read receipt event is broadcast to other room members")
    void read_receipt_broadcast() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        messageService.sendMessage(alice.getId(), room.getId(), "needs read", Message.MessageType.TEXT);

        rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "read",
                "chatRoomId", room.getId()
        ))));

        JsonNode receipt = awaitMessage(aliceSession, "read_receipt");
        assertNotNull(receipt, "alice did not receive read receipt");
        assertEquals(room.getId().longValue(), receipt.path("chatRoomId").asLong());
        assertEquals(bob.getId().longValue(), receipt.path("userId").asLong());
        assertTrue(receipt.path("lastReadMessageId").isNumber());
    }

    @Test
    @DisplayName("REST-created file message broadcast skips sender and reaches room members")
    void rest_created_file_broadcast_skips_sender() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        Message saved = messageService.sendFileMessage(
                alice.getId(),
                room.getId(),
                "doc.pdf",
                "/api/files/chat/doc.pdf",
                "application/pdf",
                3L,
                Message.MessageType.FILE
        );

        rawWebSocketHandler.broadcastMessageExcept(saved, alice.getId());

        JsonNode received = awaitMessage(bobSession, "message");
        assertNotNull(received, "bob did not receive file broadcast");
        assertEquals("doc.pdf", received.path("message").path("fileName").asText());
        assertEquals("FILE", received.path("message").path("type").asText());
        assertTrue(aliceSession.messages.isEmpty(), "sender should not receive REST echo");
    }

    @Test
    @DisplayName("Publishing app version broadcasts app_update_available to all sessions")
    void app_version_publish_broadcasts_update_to_all_sessions() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        int versionCode = 11000 + (int) (System.nanoTime() % 1000);
        AppVersionDto.PublishRequest request = new AppVersionDto.PublishRequest(
                DeviceToken.Platform.ANDROID,
                "1.1.0-test",
                versionCode,
                false,
                "ws update broadcast test"
        );

        appVersionService.publishVersion(request, null, alice.getId());

        JsonNode aliceUpdate = awaitMessage(aliceSession, "app_update_available");
        JsonNode bobUpdate = awaitMessage(bobSession, "app_update_available");
        assertNotNull(aliceUpdate, "alice missed app update broadcast");
        assertNotNull(bobUpdate, "bob missed app update broadcast");
        assertEquals("ANDROID", aliceUpdate.path("platform").asText());
        assertEquals("1.1.0-test", aliceUpdate.path("versionName").asText());
        assertEquals(versionCode, aliceUpdate.path("versionCode").asInt());
        assertEquals("ws update broadcast test", bobUpdate.path("releaseNotes").asText());
    }

    @Test
    @DisplayName("WebSocket reply is persisted and delivered to everyone with the quoted message")
    void ws_reply_persisted_and_broadcast_with_quote() throws Exception {
        Message original = messageService.sendMessage(bob.getId(), room.getId(), "original words", Message.MessageType.TEXT);
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "quoting you",
                "messageType", "TEXT",
                "replyToId", original.getId(),
                "clientMessageId", "local-abc-1"
        ))));

        JsonNode bobMsg = awaitMessage(bobSession, "message");
        assertNotNull(bobMsg, "bob missed the reply");
        assertEquals("created", bobMsg.path("event").asText());
        assertEquals(original.getId().longValue(), bobMsg.path("message").path("replyToMessageId").asLong());
        assertEquals("original words", bobMsg.path("message").path("replyToMessage").path("content").asText());
        assertEquals("Bob", bobMsg.path("message").path("replyToMessage").path("senderName").asText());

        JsonNode aliceEcho = awaitMessage(aliceSession, "message");
        assertNotNull(aliceEcho, "sender should get its own message back to settle the pending bubble");
        assertEquals("local-abc-1", aliceEcho.path("clientMessageId").asText());

        Message stored = messageService.getMessageForBroadcast(bobMsg.path("message").path("id").asLong());
        assertNotNull(stored.getReplyToMessage(), "reply_to_message_id must be stored");
        assertEquals(original.getId(), stored.getReplyToMessage().getId());
    }

    @Test
    @DisplayName("WebSocket message JSON carries every field of the REST MessageDto JSON")
    void ws_message_payload_matches_rest_dto_fields() throws Exception {
        Message original = messageService.sendMessage(bob.getId(), room.getId(), "quoted", Message.MessageType.TEXT);
        Message reply = messageService.sendEncryptedMessage(
                alice.getId(), room.getId(), "reply body", null, null, Message.MessageType.TEXT, original.getId());
        messageReactionService.addReaction(reply.getId(), bob.getId(), "👍");
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(bobSession);

        Message edited = messageService.editMessage(reply.getId(), alice.getId(), "reply body (edited)");
        rawWebSocketHandler.broadcastMessageUpdated(edited);

        JsonNode envelope = awaitMessage(bobSession, "message");
        assertNotNull(envelope, "bob missed the edit");
        assertEquals("updated", envelope.path("event").asText());
        JsonNode ws = envelope.path("message");

        JsonNode rest = objectMapper.valueToTree(
                MessageDto.fromEntity(messageService.getMessageForBroadcast(reply.getId())));
        List<String> missing = new ArrayList<>();
        rest.fieldNames().forEachRemaining(field -> {
            if (!ws.has(field)) missing.add(field);
        });
        assertTrue(missing.isEmpty(), "WS message JSON is missing REST fields: " + missing);

        // 编辑后整条替换也不能丢掉引用、表情回应和"已编辑"标记。
        assertEquals("quoted", ws.path("replyToMessage").path("content").asText());
        assertEquals("👍", ws.path("reactions").get(0).path("emoji").asText());
        assertEquals(bob.getId().longValue(), ws.path("reactions").get(0).path("userIds").get(0).asLong());
        assertFalse(ws.path("editedAt").isNull());
        assertEquals(rest.path("createdAt"), ws.path("createdAt"), "dates must serialize the same way");
    }

    @Test
    @DisplayName("Edits are pushed as updates and never re-notify offline members")
    void edit_broadcast_is_update_without_offline_push() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        drainStatus(aliceSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "first draft",
                "messageType", "TEXT"
        ))));
        JsonNode created = awaitMessage(aliceSession, "message");
        assertNotNull(created);
        verify(pushNotificationService).sendPushNotification(eq(bob.getId()), anyString(), eq("first draft"), anyString());
        clearInvocations(pushNotificationService);

        Message edited = messageService.editMessage(
                created.path("message").path("id").asLong(), alice.getId(), "second draft");
        rawWebSocketHandler.broadcastMessageUpdated(edited);

        JsonNode update = awaitMessage(aliceSession, "message");
        assertNotNull(update);
        assertEquals("updated", update.path("event").asText());
        verify(pushNotificationService, never()).sendPushNotification(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Rejected WebSocket send echoes the client message id with the reason")
    void ws_send_error_echoes_client_message_id() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ChatRoom otherRoom = chatRoomService.createGroupChat(
                alice.getId(), "other-" + suffix, "other", List.of(bob.getId()));
        Message elsewhere = messageService.sendMessage(alice.getId(), otherRoom.getId(), "elsewhere", Message.MessageType.TEXT);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(bobSession);

        rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "cross-room quote",
                "messageType", "TEXT",
                "replyToId", elsewhere.getId(),
                "clientMessageId", "local-err-1"
        ))));

        JsonNode err = awaitMessage(bobSession, "error");
        assertNotNull(err, "the sender must be told why the message was rejected");
        assertEquals("local-err-1", err.path("clientMessageId").asText());
        assertEquals("只能回复同一聊天室的消息", err.path("message").asText());
    }

    @Test
    @DisplayName("Creating a poll broadcasts the poll message; removing a vote broadcasts poll_voted")
    void poll_create_and_vote_removal_are_broadcast() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);
        Authentication aliceAuth = new UsernamePasswordAuthenticationToken(alice.getUsername(), null, List.of());

        PollDto.CreateRequest create = new PollDto.CreateRequest();
        create.setChatRoomId(room.getId());
        create.setQuestion("lunch?");
        create.setOptions(List.of("noodles", "rice"));
        PollDto poll = pollController.create(create, aliceAuth).getBody().getData();

        JsonNode bobMsg = awaitMessage(bobSession, "message");
        assertNotNull(bobMsg, "bob should see the new poll without re-entering the room");
        assertEquals("created", bobMsg.path("event").asText());
        assertEquals("POLL", bobMsg.path("message").path("messageType").asText());
        assertEquals(poll.getId().longValue(), bobMsg.path("message").path("pollId").asLong());
        verify(pushNotificationService, never()).sendPushNotification(eq(alice.getId()), anyString(), anyString(), anyString());
        drainStatus(aliceSession, bobSession);

        PollDto.VoteRequest vote = new PollDto.VoteRequest();
        vote.setOptionIndexes(List.of(0));
        pollController.vote(poll.getId(), vote, aliceAuth);
        assertNotNull(awaitMessage(bobSession, "poll_voted"));

        pollController.deleteVote(poll.getId(), aliceAuth);
        JsonNode removed = awaitMessage(bobSession, "poll_voted");
        assertNotNull(removed, "removing a vote must refresh everyone's poll card");
        assertEquals(0, removed.path("poll").path("totalVotes").asInt());
    }

    private TestWebSocketSession connect(User user) {
        TestWebSocketSession session = new TestWebSocketSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, user);
        rawWebSocketHandler.afterConnectionEstablished(session);
        openSessions.add(session);
        return session;
    }

    private User registerUser(String username, String displayName) {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setEmail(username + "@test.com");
        request.setDisplayName(displayName);
        UserDto dto = userService.registerUser(request);
        return userRepository.findById(dto.getId()).orElseThrow();
    }

    private void sendCall(TestWebSocketSession session, Map<String, Object> payload) throws Exception {
        Map<String, Object> frame = new HashMap<>(payload);
        frame.put("type", "call");
        rawWebSocketHandler.handleMessage(session, new TextMessage(objectMapper.writeValueAsString(frame)));
    }

    private SimpleServerHttpRequest request(String url) {
        return new SimpleServerHttpRequest(URI.create(url));
    }

    private void drainStatus(TestWebSocketSession... sessions) {
        for (TestWebSocketSession session : sessions) {
            for (int i = 0; i < 5; i++) {
                String msg = session.messages.poll();
                if (msg == null) break;
            }
        }
    }

    private JsonNode awaitMessage(TestWebSocketSession session, String expectedType) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String msg = session.messages.poll(3000, TimeUnit.MILLISECONDS);
            if (msg == null) return null;
            JsonNode node = objectMapper.readTree(msg);
            if (expectedType.equals(node.path("type").asText())) {
                return node;
            }
        }
        return null;
    }

    private JsonNode awaitCallAction(TestWebSocketSession session, String expectedAction) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String msg = session.messages.poll(3000, TimeUnit.MILLISECONDS);
            if (msg == null) return null;
            JsonNode node = objectMapper.readTree(msg);
            if ("call".equals(node.path("type").asText())
                    && expectedAction.equals(node.path("action").asText())) {
                return node;
            }
        }
        return null;
    }

    static class SimpleServerHttpRequest implements ServerHttpRequest {
        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();

        SimpleServerHttpRequest(URI uri) {
            this.uri = uri;
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
            throw new UnsupportedOperationException();
        }
    }

    static class TestWebSocketSession implements WebSocketSession {
        private final String id = UUID.randomUUID().toString();
        private final Map<String, Object> attributes = new HashMap<>();
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private boolean open = true;

        /** 同包的其他 WebSocket 集成测试复用这个会话桩时读收到的帧。 */
        BlockingQueue<String> messages() {
            return messages;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/api/ws");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            messages.add(message.getPayload().toString());
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            markClosed(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            markClosed(status);
        }

        void markClosed(CloseStatus status) {
            open = false;
        }
    }
}
