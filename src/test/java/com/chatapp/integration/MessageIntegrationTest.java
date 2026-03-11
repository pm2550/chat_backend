package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MessageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TokenBlacklistService tokenBlacklistService;

    @MockBean
    private PushNotificationService pushNotificationService;

    @MockBean
    private LLMService llmService;

    @MockBean
    private SelfDestructService selfDestructService;

    @MockBean
    private CloudStorageService cloudStorageService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- Helper methods ----

    private void registerUser(String username, String email, String password) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("email", email);
        request.put("password", password);
        request.put("displayName", username);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("password", password);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return (String) data.get("accessToken");
    }

    private Long getUserIdFromToken(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return ((Number) data.get("id")).longValue();
    }

    private Object[] createUserAndLogin(String userPrefix) throws Exception {
        String username = userPrefix + "_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        registerUser(username, email, password);
        String token = loginAndGetToken(username, password);
        Long userId = getUserIdFromToken(token);
        return new Object[]{token, userId};
    }

    private Long createGroupChat(String token, String name, List<Long> memberIds) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", "Test room");
        request.put("memberIds", memberIds);

        MvcResult result = mockMvc.perform(post("/api/v1/chat-rooms/group")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> chatRoom = (Map<String, Object>) responseMap.get("chatRoom");
        return ((Number) chatRoom.get("id")).longValue();
    }

    private Long sendMessage(String token, Long chatRoomId, String content) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", chatRoomId);
        request.put("content", content);

        MvcResult result = mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return ((Number) data.get("id")).longValue();
    }

    // ---- Tests ----

    @Test
    @DisplayName("Send message and retrieve messages from chat room")
    void testSendAndGetMessages() throws Exception {
        Object[] user1 = createUserAndLogin("msgsender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("msgreceiver");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Msg Test Room " + uniqueSuffix, List.of(userId2));

        sendMessage(token1, roomId, "Hello from user1!");
        sendMessage(token2, roomId, "Hello from user2!");
        sendMessage(token1, roomId, "How are you?");

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId)
                .header("Authorization", "Bearer " + token1)
                .param("page", "0")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("Get recent messages from chat room")
    void testGetRecentMessages() throws Exception {
        Object[] user1 = createUserAndLogin("recentsender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("recentreceiver");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Recent Msg Room " + uniqueSuffix, List.of(userId2));

        for (int i = 1; i <= 5; i++) {
            sendMessage(token1, roomId, "Message " + i);
        }

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId + "/recent")
                .header("Authorization", "Bearer " + token1)
                .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    @DisplayName("Mark message as read")
    void testMarkMessageAsRead() throws Exception {
        Object[] user1 = createUserAndLogin("readmarker1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("readmarker2");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Read Test Room " + uniqueSuffix, List.of(userId2));

        Long messageId = sendMessage(token1, roomId, "Read this message");

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/read")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Mark all messages as read in chat room")
    void testMarkAllMessagesAsRead() throws Exception {
        Object[] user1 = createUserAndLogin("readall1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("readall2");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "ReadAll Test Room " + uniqueSuffix, List.of(userId2));

        sendMessage(token1, roomId, "Message 1");
        sendMessage(token1, roomId, "Message 2");
        sendMessage(token1, roomId, "Message 3");

        mockMvc.perform(post("/api/v1/messages/chat-room/" + roomId + "/read-all")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reply to a message")
    void testReplyToMessage() throws Exception {
        Object[] user1 = createUserAndLogin("replier1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("replier2");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Reply Test Room " + uniqueSuffix, List.of(userId2));

        Long originalMessageId = sendMessage(token1, roomId, "Original message");

        Map<String, Object> replyRequest = new HashMap<>();
        replyRequest.put("chatRoomId", roomId);
        replyRequest.put("replyToMessageId", originalMessageId);
        replyRequest.put("content", "This is a reply");

        mockMvc.perform(post("/api/v1/messages/reply")
                .header("Authorization", "Bearer " + token2)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(replyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("This is a reply"))
                .andExpect(jsonPath("$.data.replyToMessage").exists());
    }

    @Test
    @DisplayName("Recall a message within time limit")
    void testRecallMessage() throws Exception {
        Object[] user1 = createUserAndLogin("recaller");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("recallee");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Recall Test Room " + uniqueSuffix, List.of(userId2));

        Long messageId = sendMessage(token1, roomId, "This will be recalled");

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/recall")
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cannot recall another user's message")
    void testCannotRecallOthersMessage() throws Exception {
        Object[] user1 = createUserAndLogin("recallowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("recallthief");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Recall Deny Room " + uniqueSuffix, List.of(userId2));

        Long messageId = sendMessage(token1, roomId, "User1's message");

        mockMvc.perform(post("/api/v1/messages/" + messageId + "/recall")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Search messages in chat room")
    void testSearchMessages() throws Exception {
        Object[] user1 = createUserAndLogin("searchmsg1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("searchmsg2");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Search Msg Room " + uniqueSuffix, List.of(userId2));

        String uniqueKeyword = "xyzzy_" + uniqueSuffix;
        sendMessage(token1, roomId, "Hello world");
        sendMessage(token1, roomId, "This contains " + uniqueKeyword + " keyword");
        sendMessage(token1, roomId, "Another normal message");

        mockMvc.perform(get("/api/v1/messages/search")
                .header("Authorization", "Bearer " + token1)
                .param("chatRoomId", roomId.toString())
                .param("keyword", uniqueKeyword)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Non-member cannot send message to chat room")
    void testNonMemberCannotSendMessage() throws Exception {
        Object[] user1 = createUserAndLogin("msgowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("msgoutsider");
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Exclusive Room " + uniqueSuffix, List.of());

        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("content", "I shouldn't be able to send this");

        mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", "Bearer " + token2)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Get unread message count")
    void testGetUnreadCount() throws Exception {
        Object[] user1 = createUserAndLogin("unread1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("unread2");
        Long userId2 = (Long) user2[1];
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Unread Test Room " + uniqueSuffix, List.of(userId2));

        sendMessage(token1, roomId, "Message 1");
        sendMessage(token1, roomId, "Message 2");

        mockMvc.perform(get("/api/v1/messages/unread-count")
                .header("Authorization", "Bearer " + token2)
                .param("chatRoomId", roomId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").isNumber());

        mockMvc.perform(get("/api/v1/messages/unread-count")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnreadCount").isNumber());
    }

    @Test
    @DisplayName("Delete message by sender")
    void testDeleteMessage() throws Exception {
        Object[] user1 = createUserAndLogin("delmsg1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("delmsg2");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Delete Msg Room " + uniqueSuffix, List.of(userId2));

        Long messageId = sendMessage(token1, roomId, "To be deleted");

        mockMvc.perform(delete("/api/v1/messages/" + messageId)
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Get message stats for chat room")
    void testGetMessageStats() throws Exception {
        Object[] user1 = createUserAndLogin("stats1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("stats2");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Stats Room " + uniqueSuffix, List.of(userId2));

        sendMessage(token1, roomId, "Stats message 1");
        sendMessage(token1, roomId, "Stats message 2");

        mockMvc.perform(get("/api/v1/messages/stats/" + roomId)
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.lastMessage").exists());
    }
}
