package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests: responses must NOT leak bcrypt password hashes under any path.
 * The bug this guards against: Spring's default Jackson serialization exposed
 * {@code User.password} in nested DTO responses (e.g. chat room createdBy).
 */
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
@DisplayName("Security regression — password hash must never leak")
class SecurityRegressionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private String suffix;
    private String accessToken;
    private Long myId;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "sec_" + suffix;
        String password = "SecretPass1234";

        // register
        Map<String, Object> regBody = Map.of(
                "username", username,
                "password", password,
                "email", "sec_" + suffix + "@test.com",
                "displayName", "SecUser"
        );
        String regJson = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regBody)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak("register", regJson);

        // login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        String loginJson = loginResult.getResponse().getContentAsString();
        assertNoPasswordLeak("login", loginJson);
        var jsonNode = objectMapper.readTree(loginJson).path("data");
        accessToken = jsonNode.path("accessToken").asText();
        myId = jsonNode.path("user").path("id").asLong();
    }

    @Test
    @DisplayName("create group chat does not expose creator password")
    void create_group_no_password_leak() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "sec-room-" + suffix,
                "description", "sec-test",
                "memberIds", java.util.List.of()));
        String json = mockMvc.perform(post("/api/v1/chat-rooms/group")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak("create group chat", json);
    }

    @Test
    @DisplayName("get current user profile does not expose password")
    void get_profile_no_password_leak() throws Exception {
        String json = mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak("get profile", json);
    }

    @Test
    @DisplayName("search users does not expose password")
    void search_users_no_password_leak() throws Exception {
        String json = mockMvc.perform(get("/api/profile/search?keyword=sec")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak("search users", json);
    }

    @Test
    @DisplayName("token validate endpoint does not expose password")
    void validate_no_password_leak() throws Exception {
        String json = mockMvc.perform(get("/api/auth/validate")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getContentAsString();
        assertNoPasswordLeak("validate", json);
    }

    private static void assertNoPasswordLeak(String label, String json) {
        if (json == null) return;
        String lower = json.toLowerCase();
        // bcrypt hashes start with $2a$, $2b$, $2y$ or similar
        boolean leaked = lower.contains("$2a$")
                || lower.contains("$2b$")
                || lower.contains("$2y$")
                || lower.contains("\"password\":\"")
                || lower.contains("\"password\" : \"");
        assertFalse(leaked, "password leaked in " + label + ": " + json);
    }
}
