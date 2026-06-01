package com.chatapp.integration;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.dto.AppVersionDto;
import com.chatapp.entity.DeviceToken;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
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
import static org.mockito.ArgumentMatchers.anyString;
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
    @DisplayName("Encrypted WebSocket message preserves ciphertext fields")
    void encrypted_message_broadcast() throws Exception {
        TestWebSocketSession aliceSession = connect(alice);
        TestWebSocketSession bobSession = connect(bob);
        drainStatus(aliceSession, bobSession);

        rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "[加密消息]",
                "messageType", "TEXT",
                "encryptedContent", "ZW5jcnlwdGVk",
                "encryptionVersion", 1
        ))));

        JsonNode received = awaitMessage(bobSession, "message");
        assertNotNull(received, "bob did not receive encrypted broadcast");
        assertEquals("[加密消息]", received.path("message").path("content").asText());
        assertEquals("ZW5jcnlwdGVk", received.path("message").path("encryptedContent").asText());
        assertEquals(1, received.path("message").path("encryptionVersion").asInt());
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
