package com.chatapp.integration;

import com.chatapp.service.AgentGatewayService;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.SelfDestructService;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.service.UrlPreviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试公共基座：H2 + MockMvc，外部依赖统一 mock，并提供注册/登录/建群/发消息等辅助方法。
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.main.allow-circular-references=true",
    "spring.main.allow-bean-definition-overriding=true",
    "server.servlet.context-path=",
    "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class IntegrationTestSupport {

    protected record TestUser(String token, Long id, String username) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockBean
    protected TokenBlacklistService tokenBlacklistService;

    @MockBean
    protected PushNotificationService pushNotificationService;

    @MockBean
    protected LLMService llmService;

    @MockBean
    protected AgentGatewayService agentGatewayService;

    @MockBean
    protected SelfDestructService selfDestructService;

    @MockBean
    protected CloudStorageService cloudStorageService;

    @MockBean
    protected UrlPreviewService urlPreviewService;

    protected String uniqueSuffix;

    @BeforeEach
    void setUpSupport() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(agentGatewayService.isConfigured()).thenReturn(false);
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    protected TestUser createUserAndLogin(String userPrefix) throws Exception {
        String username = userPrefix + "_" + uniqueSuffix;
        String password = "password123";

        Map<String, Object> register = new HashMap<>();
        register.put("username", username);
        register.put("email", username + "@test.com");
        register.put("password", password);
        register.put("displayName", username);
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());

        Map<String, Object> login = new HashMap<>();
        login.put("username", username);
        login.put("password", password);
        Map<String, Object> loginData = data(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn());
        String token = (String) loginData.get("accessToken");

        Map<String, Object> validated = data(mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        return new TestUser(token, ((Number) validated.get("id")).longValue(), username);
    }

    protected Long createGroupChat(TestUser owner, String name, List<Long> memberIds) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", "Test room");
        request.put("memberIds", memberIds);

        Map<String, Object> body = body(mockMvc.perform(post("/api/v1/chat-rooms/group")
                .header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        Map<String, Object> chatRoom = (Map<String, Object>) body.get("chatRoom");
        return ((Number) chatRoom.get("id")).longValue();
    }

    protected Long sendMessage(TestUser sender, Long chatRoomId, String content) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("chatRoomId", chatRoomId);
        request.put("content", content);

        Map<String, Object> data = data(mockMvc.perform(post("/api/v1/messages")
                .header("Authorization", sender.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn());
        return ((Number) data.get("id")).longValue();
    }

    protected void updateDisplayState(TestUser user, Long chatRoomId, String action) throws Exception {
        mockMvc.perform(put("/api/v1/chat-rooms/" + chatRoomId + "/display-state")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("action", action))))
                .andExpect(status().isOk());
    }

    protected Map<String, Object> body(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    protected Map<String, Object> data(MvcResult result) throws Exception {
        return (Map<String, Object>) body(result).get("data");
    }
}
