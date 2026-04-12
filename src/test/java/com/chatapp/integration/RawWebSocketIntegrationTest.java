package com.chatapp.integration;

import com.chatapp.dto.UserDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserService;
import com.chatapp.util.JwtUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
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

    @LocalServerPort
    int port;

    @Autowired private JwtUtils jwtUtils;
    @Autowired private UserService userService;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private UserRepository userRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;
    private ChatRoom room;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);

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
        bobToken = jwtUtils.generateAccessToken(bob.getUsername());

        room = chatRoomService.createGroupChat(
                alice.getId(), "test-room-" + suffix, "ws-test",
                List.of(bob.getId()));
    }

    @Test
    @DisplayName("Handshake rejected without token")
    void handshake_requires_token() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/api/ws");
        CaptureHandler handler = new CaptureHandler();
        Throwable error = null;
        try {
            client.execute(handler, uri.toString()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            error = e;
        }
        assertTrue(error != null || handler.closed.getNow(null) != null,
                "expected handshake failure without token");
    }

    @Test
    @DisplayName("Handshake rejected with malformed token")
    void handshake_rejects_malformed_token() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/api/ws?token=not-a-real-jwt";
        CaptureHandler handler = new CaptureHandler();
        Throwable error = null;
        try {
            client.execute(handler, url).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            error = e;
        }
        assertTrue(error != null || handler.closed.getNow(null) != null,
                "expected handshake failure with bogus token");
    }

    @Test
    @DisplayName("Handshake rejected when token is blacklisted")
    void handshake_rejects_blacklisted_token() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/api/ws?token=" + aliceToken;
        CaptureHandler handler = new CaptureHandler();
        Throwable error = null;
        try {
            client.execute(handler, url).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            error = e;
        }
        assertTrue(error != null || handler.closed.getNow(null) != null,
                "expected handshake failure with blacklisted token");

        // Reset for other tests
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("Three-party broadcast delivers single message to both non-sender members")
    void three_party_broadcast() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto.RegisterRequest carolReq = new UserDto.RegisterRequest();
        carolReq.setUsername("carol_" + suffix);
        carolReq.setPassword("password123");
        carolReq.setEmail("carol_" + suffix + "@test.com");
        carolReq.setDisplayName("Carol");
        UserDto carolDto = userService.registerUser(carolReq);
        User carol = userRepository.findById(carolDto.getId()).orElseThrow();
        String carolToken = jwtUtils.generateAccessToken(carol.getUsername());

        ChatRoom bigRoom = chatRoomService.createGroupChat(
                alice.getId(), "trio-" + suffix, "trio",
                List.of(bob.getId(), carol.getId()));

        CaptureHandler ah = new CaptureHandler();
        CaptureHandler bh = new CaptureHandler();
        CaptureHandler ch = new CaptureHandler();
        WebSocketSession as = connect(ah, aliceToken);
        WebSocketSession bs = connect(bh, bobToken);
        WebSocketSession cs = connect(ch, carolToken);

        // drain initial status broadcasts
        for (CaptureHandler h : List.of(ah, bh, ch)) {
            for (int i = 0; i < 5; i++) {
                String msg = h.messages.poll(300, TimeUnit.MILLISECONDS);
                if (msg == null) break;
            }
        }

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message",
                "chatRoomId", bigRoom.getId(),
                "content", "ping all",
                "messageType", "TEXT"
        ));
        as.sendMessage(new TextMessage(payload));

        JsonNode bMsg = awaitMessage(bh, "message");
        JsonNode cMsg = awaitMessage(ch, "message");
        assertNotNull(bMsg, "bob missed broadcast");
        assertNotNull(cMsg, "carol missed broadcast");
        assertEquals("ping all", bMsg.path("message").path("content").asText());
        assertEquals("ping all", cMsg.path("message").path("content").asText());

        as.close(CloseStatus.NORMAL);
        bs.close(CloseStatus.NORMAL);
        cs.close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("Sending message to a room user is not a member of is rejected")
    void send_requires_membership() throws Exception {
        // Create a lone room where only alice is a member
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ChatRoom isolated = chatRoomService.createGroupChat(
                alice.getId(), "isolated-" + suffix, "isolated", List.of());

        CaptureHandler bobHandler = new CaptureHandler();
        WebSocketSession bobSession = connect(bobHandler, bobToken);
        bobHandler.messages.poll(500, TimeUnit.MILLISECONDS);

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message",
                "chatRoomId", isolated.getId(),
                "content", "intruder",
                "messageType", "TEXT"
        ));
        bobSession.sendMessage(new TextMessage(payload));
        // handler should receive an error frame, not a message broadcast
        JsonNode err = awaitMessage(bobHandler, "error");
        assertNotNull(err, "bob should receive error frame when sending to a room he isn't in");
        bobSession.close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("Alice sends message, Bob receives broadcast")
    void broadcast_roundtrip() throws Exception {
        CaptureHandler aliceHandler = new CaptureHandler();
        CaptureHandler bobHandler = new CaptureHandler();

        WebSocketSession aliceSession = connect(aliceHandler, aliceToken);
        WebSocketSession bobSession = connect(bobHandler, bobToken);

        // Drain any initial status broadcasts
        aliceHandler.messages.poll(500, TimeUnit.MILLISECONDS);
        bobHandler.messages.poll(500, TimeUnit.MILLISECONDS);

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "message",
                "chatRoomId", room.getId(),
                "content", "hello from alice",
                "messageType", "TEXT"
        ));
        aliceSession.sendMessage(new TextMessage(payload));

        JsonNode received = awaitMessage(bobHandler, "message");
        assertNotNull(received, "bob did not receive broadcast within timeout");
        assertEquals("hello from alice", received.path("message").path("content").asText());
        assertEquals(alice.getId().longValue(), received.path("message").path("senderId").asLong());

        aliceSession.close(CloseStatus.NORMAL);
        bobSession.close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("Ping receives pong")
    void ping_pong() throws Exception {
        CaptureHandler handler = new CaptureHandler();
        WebSocketSession session = connect(handler, aliceToken);
        session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        JsonNode pong = awaitMessage(handler, "pong");
        assertNotNull(pong, "expected pong");
        session.close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("Typing event broadcast to other room members")
    void typing_broadcast() throws Exception {
        CaptureHandler aliceHandler = new CaptureHandler();
        CaptureHandler bobHandler = new CaptureHandler();
        WebSocketSession aliceSession = connect(aliceHandler, aliceToken);
        WebSocketSession bobSession = connect(bobHandler, bobToken);

        aliceHandler.messages.poll(500, TimeUnit.MILLISECONDS);
        bobHandler.messages.poll(500, TimeUnit.MILLISECONDS);

        String typingPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "type", "typing",
                "chatRoomId", room.getId(),
                "isTyping", true
        ));
        aliceSession.sendMessage(new TextMessage(typingPayload));

        JsonNode received = awaitMessage(bobHandler, "typing");
        assertNotNull(received, "bob did not receive typing event");
        assertEquals(true, received.path("isTyping").asBoolean());
        assertEquals(alice.getId().longValue(), received.path("userId").asLong());

        aliceSession.close(CloseStatus.NORMAL);
        bobSession.close(CloseStatus.NORMAL);
    }

    private WebSocketSession connect(WebSocketHandler handler, String token) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/api/ws?token=" + token;
        return client.execute(handler, url).get(5, TimeUnit.SECONDS);
    }

    private JsonNode awaitMessage(CaptureHandler handler, String expectedType) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String msg = handler.messages.poll(3000, TimeUnit.MILLISECONDS);
            if (msg == null) return null;
            JsonNode node = objectMapper.readTree(msg);
            if (expectedType.equals(node.path("type").asText())) {
                return node;
            }
            // loop past unrelated events (status)
        }
        return null;
    }

    static class CaptureHandler extends AbstractWebSocketHandler {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }
    }
}
