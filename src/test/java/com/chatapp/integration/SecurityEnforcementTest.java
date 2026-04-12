package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security enforcement tests: blacklist, rate limit, missing token.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.servlet.context-path=",
        "rate-limit.requests-per-minute=2000",
        "rate-limit.auth-requests-per-minute=2000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Security enforcement")
class SecurityEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private String accessToken;
    private Set<String> generatedTokenIds = new HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> reg = Map.of(
                "username", "sec_" + suffix,
                "password", "Pass1234",
                "email", "sec_" + suffix + "@test.com",
                "displayName", "SecUser");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());

        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "sec_" + suffix,
                                "password", "Pass1234"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        accessToken = objectMapper.readTree(loginJson).path("data").path("accessToken").asText();
    }

    @Test
    @DisplayName("Protected endpoint rejects request without Authorization header")
    void protected_requires_auth() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Protected endpoint accepts valid Bearer token")
    void protected_accepts_valid_token() throws Exception {
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Blacklisted token denies access to protected endpoint")
    void blacklisted_token_denied() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
        // reset
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("Logout blacklists the token so subsequent protected requests are denied")
    void logout_then_denied() throws Exception {
        // stub blacklist to reflect whatever the service calls
        // When AuthController calls blacklistToken(id, ttl), we'll remember it and say isBlacklisted returns true afterwards.
        org.mockito.Mockito.doAnswer(inv -> {
            generatedTokenIds.add(inv.getArgument(0));
            when(tokenBlacklistService.isBlacklisted(anyString())).thenAnswer(inv2 -> {
                String id = inv2.getArgument(0);
                return generatedTokenIds.contains(id);
            });
            return null;
        }).when(tokenBlacklistService).blacklistToken(anyString(), anyLong());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // same token must now be rejected
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());

        // reset
        generatedTokenIds.clear();
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("Malformed Bearer token does not authenticate")
    void malformed_bearer_denied() throws Exception {
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Refresh token cannot be used as access token")
    void refresh_token_not_access() throws Exception {
        // Extract refresh token from login response
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> reg = Map.of(
                "username", "ref_" + suffix, "password", "Pass1234",
                "email", "ref_" + suffix + "@test.com", "displayName", "RefUser");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isOk());
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ref_" + suffix, "password", "Pass1234"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(loginJson).path("data");
        String refreshToken = data.path("refreshToken").asText();

        // Using refresh token as bearer on a protected endpoint should be denied
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isForbidden());
    }
}
