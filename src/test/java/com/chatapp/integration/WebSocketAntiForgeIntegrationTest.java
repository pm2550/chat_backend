package com.chatapp.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UserService;
import com.chatapp.service.tool.PendingClientCallRegistry;
import com.chatapp.websocket.CallRoomRegistry;
import com.chatapp.websocket.RawWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@DisplayName("WebSocket anti-forge integration")
class WebSocketAntiForgeIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RawWebSocketHandler rawWebSocketHandler;
    @Autowired private PendingClientCallRegistry pendingClientCallRegistry;
    @Autowired private CallRoomRegistry callRoomRegistry;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private final List<RawWebSocketIntegrationTest.TestWebSocketSession> sessions = new ArrayList<>();
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        callRoomRegistry.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = registerUser("forge_alice_" + suffix, "Forge Alice");
        bob = registerUser("forge_bob_" + suffix, "Forge Bob");
    }

    @AfterEach
    void tearDown() {
        for (RawWebSocketIntegrationTest.TestWebSocketSession session : sessions) {
            rawWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
            session.markClosed(CloseStatus.NORMAL);
        }
        sessions.clear();
        callRoomRegistry.clear();
    }

    @Test
    @DisplayName("forged client tool result from another session is rejected and legit reply still completes")
    void forgedClientToolResultFromOtherSessionIsRejected() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PendingClientCallRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RawWebSocketIntegrationTest.TestWebSocketSession aliceSession = connect(alice);
            RawWebSocketIntegrationTest.TestWebSocketSession bobSession = connect(bob);
            UUID callId = UUID.randomUUID();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingClientCallRegistry.register(callId, alice.getId(), future, 5000);

            rawWebSocketHandler.handleMessage(bobSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "agent_tool_result",
                    "callId", callId.toString(),
                    "result", Map.of("forged", true)
            ))));

            TimeUnit.MILLISECONDS.sleep(200);
            assertFalse(future.isDone(), "forged reply must not complete Alice's pending client call");
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("Dropping forged agent client tool result")),
                    "forged reply should be logged at WARN");

            rawWebSocketHandler.handleMessage(aliceSession, new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "agent_tool_result",
                    "callId", callId.toString(),
                    "result", Map.of("legit", true)
            ))));

            JsonNode result = future.get(1, TimeUnit.SECONDS);
            assertEquals(true, result.path("legit").asBoolean());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private RawWebSocketIntegrationTest.TestWebSocketSession connect(User user) {
        RawWebSocketIntegrationTest.TestWebSocketSession session =
                new RawWebSocketIntegrationTest.TestWebSocketSession();
        session.getAttributes().put(RawWebSocketHandler.ATTR_USER, user);
        rawWebSocketHandler.afterConnectionEstablished(session);
        sessions.add(session);
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
}
