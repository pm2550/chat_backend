package com.chatapp.integration;

import com.chatapp.dto.BotDto;
import com.chatapp.dto.UrlPreviewDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.service.AgentGatewayService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UrlPreviewService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false",
    "file.storage.upload-dir=target/test-uploads/message-integration"
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
    private AgentGatewayService agentGatewayService;

    @MockBean
    private SelfDestructService selfDestructService;

    @MockBean
    private CloudStorageService cloudStorageService;

    @MockBean
    private UrlPreviewService urlPreviewService;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(agentGatewayService.isConfigured()).thenReturn(false);
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

    private Long createWorkspace(String token, String name, String type, boolean botAccess) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("workspaceType", type);
        request.put("botAccessEnabled", botAccess);

        MvcResult result = mockMvc.perform(post("/api/v1/workspaces")
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

    private Map<String, Object> sendFileMessage(
            String token,
            Long chatRoomId,
            MockMultipartFile file) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/messages/file")
                .file(file)
                .param("chatRoomId", chatRoomId.toString())
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        return (Map<String, Object>) responseMap.get("data");
    }

    private Long createBot(String token, String botName) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("botName", botName);
        request.put("llmProvider", BotConfig.LLMProvider.OLLAMA.name());
        request.put("modelName", "test-model");
        request.put("systemPrompt", "Be brief");

        MvcResult result = mockMvc.perform(post("/api/v1/bots")
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

    private void addBotToRoom(String token, Long roomId, Long botId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("triggerMode", ChatRoomBot.TriggerMode.MENTION.name());

        mockMvc.perform(post("/api/v1/bots/chat-rooms/" + roomId + "/bots/" + botId + "/add")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // ---- Tests ----

    @Test
    @DisplayName("Text message with HTTPS URL persists link preview after commit")
    void textMessageWithHttpsUrlPersistsLinkPreviewAfterCommit() throws Exception {
        Object[] user = createUserAndLogin("linkowner");
        String token = (String) user[0];
        Long roomId = createGroupChat(token, "Link Preview Room " + uniqueSuffix, List.of());

        when(urlPreviewService.fetch(eq("https://example.com/"))).thenReturn(new UrlPreviewDto(
                "https://example.com/",
                "Example Domain",
                "Example description",
                null,
                "example.com",
                "https://example.com/favicon.ico"));

        Long messageId = sendMessage(token, roomId, "看看这个链接 https://example.com/");

        boolean found = false;
        for (int i = 0; i < 20; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/messages/chat-room/{chatRoomId}", roomId)
                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            Map<String, Object> responseMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
            List<Map<String, Object>> messages = (List<Map<String, Object>>) responseMap.get("messages");
            for (Map<String, Object> message : messages) {
                if (((Number) message.get("id")).longValue() == messageId) {
                    Map<String, Object> preview = (Map<String, Object>) message.get("linkPreview");
                    found = preview != null && "Example Domain".equals(preview.get("title"));
                    break;
                }
            }
            if (found) {
                break;
            }
            Thread.sleep(100);
        }

        assertTrue(found, "linkPreview should be persisted after the message transaction commits");
        verify(urlPreviewService, times(1)).fetch(eq("https://example.com/"));
    }

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
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.messages[0].sender.password").doesNotExist())
                .andExpect(jsonPath("$.messages[0].sender.roles").doesNotExist())
                .andExpect(jsonPath("$.messages[0].senderId").isNumber())
                .andExpect(jsonPath("$.messages[0].chatRoomId").value(roomId));
    }

    @Test
    @DisplayName("Send anonymous message and retrieve anonymous display metadata")
    void testSendAnonymousMessage() throws Exception {
        Object[] user1 = createUserAndLogin("anon_sender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("anon_receiver");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Anon Room " + uniqueSuffix, List.of(userId2));

        mockMvc.perform(put("/api/v1/chat-rooms/" + roomId + "/anonymous/toggle")
                .header("Authorization", "Bearer " + token1)
                .param("enable", "true"))
                .andExpect(status().isOk());

        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("content", "anonymous hello");
        request.put("isAnonymous", true);

        mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", "Bearer " + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAnonymous").value(true))
                .andExpect(jsonPath("$.data.anonymousIdentityId").isNumber())
                .andExpect(jsonPath("$.data.anonymousName").isString())
                .andExpect(jsonPath("$.data.senderName").isString());

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId)
                .header("Authorization", "Bearer " + token1)
                .param("page", "0")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("anonymous hello"))
                .andExpect(jsonPath("$.messages[0].isAnonymous").value(true))
                .andExpect(jsonPath("$.messages[0].anonymousName").isString());
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
    @DisplayName("Send encrypted text message preserves ciphertext envelope")
    void testSendEncryptedTextMessage() throws Exception {
        Object[] user1 = createUserAndLogin("encryptedsender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("encryptedreceiver");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Encrypted Room " + uniqueSuffix, List.of(userId2));
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("content", "[加密消息]");
        request.put("encryptedContent", "ZW5jcnlwdGVk");
        request.put("encryptionVersion", 1);

        mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", "Bearer " + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("[加密消息]"))
                .andExpect(jsonPath("$.data.encryptedContent").value("ZW5jcnlwdGVk"))
                .andExpect(jsonPath("$.data.encryptionVersion").value(1));
    }

    @Test
    @DisplayName("Send image message through multipart endpoint")
    void testSendImageFileMessage() throws Exception {
        Object[] user1 = createUserAndLogin("imagesender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("imagereceiver");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Image Room " + uniqueSuffix, List.of(userId2));
        MockMultipartFile image = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2, 3, 4});

        Map<String, Object> data = sendFileMessage(token1, roomId, image);

        org.assertj.core.api.Assertions.assertThat(data.get("messageType")).isEqualTo("IMAGE");
        org.assertj.core.api.Assertions.assertThat(data.get("fileName")).isEqualTo("photo.png");
        org.assertj.core.api.Assertions.assertThat((String) data.get("fileUrl")).startsWith("/api/files/chat/");
        org.assertj.core.api.Assertions.assertThat(((Number) data.get("fileSize")).longValue()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Send regular file through multipart endpoint")
    void testSendRegularFileMessage() throws Exception {
        Object[] user1 = createUserAndLogin("filesender");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("filereceiver");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "File Room " + uniqueSuffix, List.of(userId2));
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{5, 6, 7});

        Map<String, Object> data = sendFileMessage(token1, roomId, file);

        org.assertj.core.api.Assertions.assertThat(data.get("messageType")).isEqualTo("FILE");
        org.assertj.core.api.Assertions.assertThat(data.get("fileName")).isEqualTo("doc.pdf");
        org.assertj.core.api.Assertions.assertThat((String) data.get("fileUrl")).startsWith("/api/files/chat/");
        org.assertj.core.api.Assertions.assertThat(((Number) data.get("fileSize")).longValue()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Non-member cannot send file message")
    void testNonMemberCannotSendFileMessage() throws Exception {
        Object[] user1 = createUserAndLogin("fileowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("fileintruder");
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Private File Room " + uniqueSuffix, List.of());
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/messages/file")
                .file(file)
                .param("chatRoomId", roomId.toString())
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("不是该聊天室的成员")));
    }

    @Test
    @DisplayName("Chat file download requires authentication")
    void testChatFileDownloadRequiresAuthentication() throws Exception {
        Object[] user1 = createUserAndLogin("downloadsender");
        String token1 = (String) user1[0];

        Long roomId = createGroupChat(token1, "Download Room " + uniqueSuffix, List.of());
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes());
        Map<String, Object> data = sendFileMessage(token1, roomId, file);
        String fileUrl = (String) data.get("fileUrl");

        mockMvc.perform(get(fileUrl))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get(fileUrl)
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    @DisplayName("Chat file download requires room membership")
    void testChatFileDownloadRequiresRoomMembership() throws Exception {
        Object[] user1 = createUserAndLogin("aclowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("aclintruder");
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "ACL Room " + uniqueSuffix, List.of());
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.txt", "text/plain", "secret".getBytes());
        Map<String, Object> data = sendFileMessage(token1, roomId, file);
        String fileUrl = (String) data.get("fileUrl");

        mockMvc.perform(get(fileUrl)
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Chat file center lists image and file messages with filters")
    void testChatFileCenterListsAttachments() throws Exception {
        Object[] user1 = createUserAndLogin("filecenter1");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("filecenter2");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "File Center Room " + uniqueSuffix, List.of(userId2));
        sendMessage(token1, roomId, "plain text");
        sendFileMessage(token1, roomId, new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2}));
        sendFileMessage(token1, roomId, new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{3, 4, 5}));

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId + "/files")
                .header("Authorization", "Bearer " + token1)
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.messages[*].fileName", hasItems("photo.png", "doc.pdf")));

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId + "/files")
                .header("Authorization", "Bearer " + token1)
                .param("messageType", "IMAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.messages[0].fileName").value("photo.png"));
    }

    @Test
    @DisplayName("Agent task runs workflow and writes result message")
    void testAgentTaskWorkflow() throws Exception {
        Object[] user1 = createUserAndLogin("agentuser");
        String token1 = (String) user1[0];
        Long userId1 = (Long) user1[1];

        Long roomId = createGroupChat(token1, "Agent Room " + uniqueSuffix, List.of());
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("prompt", "summarize this room");

        MvcResult result = mockMvc.perform(post("/api/v1/agent-tasks")
                .header("Authorization", "Bearer " + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result").value("任务已接收: summarize this room"))
                .andExpect(jsonPath("$.data.resultMessage.content").value("任务已接收: summarize this room"))
                .andExpect(jsonPath("$.data.resultMessage.content", not(startsWith("[Agent]"))))
                .andExpect(jsonPath("$.data.resultMessage.senderId").value(userId1))
                .andExpect(jsonPath("$.data.resultMessage.botConfigId").isNumber())
                .andExpect(jsonPath("$.data.resultMessage.botSenderId").isNumber())
                .andExpect(jsonPath("$.data.resultMessage.botName").value("Agent"))
                .andExpect(jsonPath("$.data.resultMessage.botAvatar").value("/assets/agent-avatar.png"))
                .andReturn();

        Map<String, Object> responseMap = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        Long taskId = ((Number) data.get("id")).longValue();

        mockMvc.perform(get("/api/v1/agent-tasks/" + taskId)
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(taskId));

        mockMvc.perform(get("/api/v1/agent-tasks")
                .header("Authorization", "Bearer " + token1)
                .param("chatRoomId", roomId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[0].id").value(taskId));
    }

    @Test
    @DisplayName("Agent task can save its result into a service workspace")
    void testAgentTaskSavesArtifactToWorkspace() throws Exception {
        Object[] user1 = createUserAndLogin("agentartifact");
        String token1 = (String) user1[0];

        Long roomId = createGroupChat(token1, "Agent Artifact Room " + uniqueSuffix, List.of());
        Long workspaceId = createWorkspace(token1, "Agent Artifact Vault " + uniqueSuffix, "SERVICE", true);

        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("prompt", "write release notes");
        request.put("artifactWorkspaceId", workspaceId);
        request.put("artifactFileName", "agent-release-notes.txt");

        mockMvc.perform(post("/api/v1/agent-tasks")
                .header("Authorization", "Bearer " + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.artifactWorkspaceId").value(workspaceId))
                .andExpect(jsonPath("$.data.artifactFileId").exists())
                .andExpect(jsonPath("$.data.artifactFileName").value("agent-release-notes.txt"))
                .andExpect(jsonPath("$.data.result", containsString("任务已接收: write release notes")))
                .andExpect(jsonPath("$.data.result", containsString("[Artifact] 已保存到资料库")));

        MvcResult contentsResult = mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/contents")
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].displayName").value("agent-release-notes.txt"))
                .andExpect(jsonPath("$.data.files[0].sourceType").value("SERVICE"))
                .andExpect(jsonPath("$.data.files[0].scanStatus").value("CLEAN"))
                .andReturn();

        Map<String, Object> responseMap = objectMapper.readValue(contentsResult.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        List<Map<String, Object>> files = (List<Map<String, Object>>) data.get("files");
        Long fileId = ((Number) files.get(0).get("id")).longValue();

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/files/" + fileId + "/download")
                .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(content().bytes("任务已接收: write release notes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Agent task uses external gateway when configured")
    void testAgentTaskUsesConfiguredGateway() throws Exception {
        Object[] user1 = createUserAndLogin("gatewayuser");
        String token1 = (String) user1[0];

        Long roomId = createGroupChat(token1, "Gateway Room " + uniqueSuffix, List.of());
        when(agentGatewayService.isConfigured()).thenReturn(true);
        when(agentGatewayService.execute(any())).thenReturn("gateway completed");

        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", roomId);
        request.put("prompt", "openclaw do work");

        mockMvc.perform(post("/api/v1/agent-tasks")
                .header("Authorization", "Bearer " + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result").value("gateway completed"))
                .andExpect(jsonPath("$.data.resultMessage.content").value("gateway completed"))
                .andExpect(jsonPath("$.data.resultMessage.botConfigId").isNumber())
                .andExpect(jsonPath("$.data.resultMessage.botName").value("Agent"));

        verify(agentGatewayService).execute(any());
    }

    @Test
    @DisplayName("Non-member cannot list chat file center")
    void testNonMemberCannotListChatFileCenter() throws Exception {
        Object[] user1 = createUserAndLogin("filecenterowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("filecenterintruder");
        String token2 = (String) user2[0];

        Long roomId = createGroupChat(token1, "Private File Center " + uniqueSuffix, List.of());

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId + "/files")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("不是该聊天室的成员")));
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
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.messages[0].content", containsString(uniqueKeyword)))
                .andExpect(jsonPath("$.messages[0].sender.password").doesNotExist())
                .andExpect(jsonPath("$.messages[0].sender.roles").doesNotExist());
    }

    @Test
    @DisplayName("Mentioned room bot responds through the main message pipeline")
    void testMentionedBotRespondsToMessage() throws Exception {
        Object[] user1 = createUserAndLogin("botowner");
        String token1 = (String) user1[0];

        Object[] user2 = createUserAndLogin("botmember");
        Long userId2 = (Long) user2[1];

        Long roomId = createGroupChat(token1, "Bot Room " + uniqueSuffix, List.of(userId2));
        Long botId = createBot(token1, "HelperBot");
        addBotToRoom(token1, roomId, botId);

        when(llmService.chat(any(), any()))
                .thenReturn(new BotDto.LLMResponse("bot answer", 3, "test-model"));

        sendMessage(token1, roomId, "@HelperBot hello");

        mockMvc.perform(get("/api/v1/messages/chat-room/" + roomId)
                .header("Authorization", "Bearer " + token1)
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.messages[*].content", hasItem("bot answer")))
                .andExpect(jsonPath("$.messages[*].botConfigId", hasItem(botId.intValue())))
                .andExpect(jsonPath("$.messages[*].botName", hasItem("HelperBot")));
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
                .andExpect(jsonPath("$.unreadCount").value(2));

        mockMvc.perform(get("/api/v1/messages/unread-count")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnreadCount").value(2));

        verify(pushNotificationService, times(2))
                .sendPushNotification(eq(userId2), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/messages/chat-room/" + roomId + "/read-all")
                .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/messages/unread-count")
                .header("Authorization", "Bearer " + token2)
                .param("chatRoomId", roomId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
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
                .andExpect(jsonPath("$.lastMessage").exists())
                .andExpect(jsonPath("$.lastMessage.sender.password").doesNotExist())
                .andExpect(jsonPath("$.lastMessage.sender.roles").doesNotExist());
    }
}
