package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "rate-limit.requests-per-minute=2000",
        "rate-limit.auth-requests-per-minute=2000",
        "provider-vault.master-key=test-provider-vault-master-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Provider credential vault integration")
class ProviderCredentialIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        ownerToken = registerAndLogin("vault_owner_", "Vault Owner");
        otherToken = registerAndLogin("vault_other_", "Vault Other");
    }

    @Test
    @DisplayName("provider credential endpoints require authentication")
    void providerCredentialEndpoints_requireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/provider-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/v1/provider-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"prod","llmProvider":"OPENAI","secret":"sk-test"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("credential create/list never returns plaintext secret")
    void credentialCreateAndList_neverReturnsPlaintextSecret() throws Exception {
        String secret = "sk-live-super-secret-1234";

        String createJson = createCredential(ownerToken, "OPENAI", "OpenAI Prod", secret);
        assertFalse(createJson.contains(secret), "plaintext secret leaked in create response: " + createJson);

        mockMvc.perform(get("/api/v1/provider-credentials?provider=OPENAI")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].label").value("OpenAI Prod"))
                .andExpect(jsonPath("$.data[0].secretLast4").value("1234"))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andDo(result -> assertFalse(
                        result.getResponse().getContentAsString().contains(secret),
                        "plaintext secret leaked in list response"));
    }

    @Test
    @DisplayName("bot owner sees credential metadata while other users cannot fetch it")
    void botCredentialMetadata_ownerOnly() throws Exception {
        long credentialId = credentialId(createCredential(ownerToken, "OPENAI", "Owner Key", "sk-owner-0001"));
        long botId = createBot(ownerToken, credentialId);

        mockMvc.perform(get("/api/v1/bots/" + botId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerCredentialId").value(credentialId))
                .andExpect(jsonPath("$.data.providerCredentialLabel").value("Owner Key"))
                .andExpect(jsonPath("$.data.providerCredentialLast4").value("0001"))
                .andExpect(jsonPath("$.data.hasCredential").value(true));

        mockMvc.perform(get("/api/v1/bots/" + botId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("room bot list hides credential metadata from general room context")
    void roomBotList_hidesCredentialMetadata() throws Exception {
        long credentialId = credentialId(createCredential(ownerToken, "OPENAI", "Room Key", "sk-room-0002"));
        long botId = createBot(ownerToken, credentialId);
        long roomId = createGroupRoom(ownerToken);

        mockMvc.perform(post("/api/v1/bots/chat-rooms/" + roomId + "/bots/" + botId + "/add")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/bots/chat-rooms/" + roomId + "/bots")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hasCredential").value(true))
                .andExpect(jsonPath("$.data[0].providerCredentialId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].providerCredentialLabel").value(nullValue()))
                .andExpect(jsonPath("$.data[0].providerCredentialLast4").value(nullValue()));
    }

    private String registerAndLogin(String prefix, String displayName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = prefix + suffix;
        String password = "SecretPass1234";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "email", username + "@test.com",
                                "displayName", displayName))))
                .andExpect(status().isOk());

        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginJson).path("data").path("accessToken").asText();
    }

    private String createCredential(
            String token,
            String provider,
            String label,
            String secret) throws Exception {
        return mockMvc.perform(post("/api/v1/provider-credentials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "llmProvider", provider,
                                "label", label,
                                "secret", secret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.label").value(label))
                .andReturn().getResponse().getContentAsString();
    }

    private long credentialId(String responseJson) throws Exception {
        return objectMapper.readTree(responseJson).path("data").path("id").asLong();
    }

    private long createBot(String token, long credentialId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/bots")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "botName", "Vault Bot " + UUID.randomUUID().toString().substring(0, 6),
                                "llmProvider", "OPENAI",
                                "providerCredentialId", credentialId,
                                "modelName", "gpt-test",
                                "systemPrompt", "You are a test bot",
                                "temperature", 0.7,
                                "maxTokens", 128))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerCredentialId").value(credentialId))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private long createGroupRoom(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v1/chat-rooms/group")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "vault-room-" + UUID.randomUUID().toString().substring(0, 6),
                                "description", "provider vault test",
                                "memberIds", List.of()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        JsonNode data = root.has("data") ? root.path("data") : root;
        JsonNode room = data.has("chatRoom") ? data.path("chatRoom") : data;
        return room.path("id").asLong();
    }
}
