package com.chatapp.integration;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class ClientHashAuthIntegrationTest {

    private static final String PARAMS = "m=65536,t=3,p=1,v=19,hashLen=32";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

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

    private String suffix;

    @BeforeEach
    void setUp() {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("Salt endpoint returns real params for client-hash users")
    void saltEndpointExistingArgon2User() throws Exception {
        String username = "salt_argon_" + suffix;
        registerClientUser(username, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAA");

        mockMvc.perform(get("/api/auth/client-salt-params")
                        .param("username", username))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.salt").value("AAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.data.argon2Params").value(PARAMS))
                .andExpect(jsonPath("$.data.scheme").value("CLIENT_ARGON2_BCRYPT"));
    }

    @Test
    @DisplayName("Salt endpoint returns deterministic fake params for legacy users")
    void saltEndpointExistingLegacyUser() throws Exception {
        String username = "salt_legacy_" + suffix;
        registerLegacyUser(username, "Password123!");

        MvcResult first = mockMvc.perform(get("/api/auth/client-salt-params")
                        .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheme").value("BCRYPT_LEGACY"))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/auth/client-salt-params")
                        .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheme").value("BCRYPT_LEGACY"))
                .andReturn();

        assertThat(data(first).get("salt")).isEqualTo(data(second).get("salt"));
    }

    @Test
    @DisplayName("Salt endpoint returns legacy-shaped fake params for nonexistent users")
    void saltEndpointNonexistentUser() throws Exception {
        String username = "missing_" + suffix;
        MvcResult first = mockMvc.perform(get("/api/auth/client-salt-params")
                        .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheme").value("BCRYPT_LEGACY"))
                .andExpect(jsonPath("$.data.argon2Params").value(PARAMS))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/auth/client-salt-params")
                        .param("username", username))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(data(first).keySet()).containsExactlyInAnyOrder("salt", "argon2Params", "scheme");
        assertThat(data(first).get("salt")).isEqualTo(data(second).get("salt"));
    }

    @Test
    @DisplayName("Salt endpoint per-username throttle returns 429 after 30 lookups")
    void saltEndpointPerUsernameThrottle429After30() throws Exception {
        String username = "throttle_" + suffix;
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/auth/client-salt-params")
                            .header("X-Forwarded-For", "10.9.0." + i)
                            .param("username", username))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/auth/client-salt-params")
                        .header("X-Forwarded-For", "10.9.0.99")
                        .param("username", username))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));
    }

    @Test
    @DisplayName("Register stores client-hash users with client scheme")
    void registerNewClientPath() throws Exception {
        String username = "reg_client_" + suffix;
        registerClientUser(username, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", "BBBBBBBBBBBBBBBBBBBBBB");

        User user = userRepository.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordScheme()).isEqualTo("CLIENT_ARGON2_BCRYPT");
        assertThat(user.getClientSalt()).isEqualTo("BBBBBBBBBBBBBBBBBBBBBB");
        assertThat(user.getPassword()).startsWith("$2");
    }

    @Test
    @DisplayName("Register keeps legacy clients on legacy scheme")
    void registerLegacyClientPath() throws Exception {
        String username = "reg_legacy_" + suffix;
        registerLegacyUser(username, "Password123!");

        User user = userRepository.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordScheme()).isEqualTo("BCRYPT_LEGACY");
        assertThat(user.getClientSalt()).isNull();
        assertThat(user.getPassword()).startsWith("$2");
    }

    @Test
    @DisplayName("Register rejects mixed legacy and client credentials")
    void registerRejectsMixedCredentials() throws Exception {
        Map<String, Object> body = clientRegisterBody(
                "mixed_" + suffix, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "CCCCCCCCCCCCCCCCCCCCCC");
        body.put("password", "Password123!");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register rejects weak Argon2 params")
    void registerRejectsWeakArgon2Params() throws Exception {
        Map<String, Object> body = clientRegisterBody(
                "weak_" + suffix, "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", "DDDDDDDDDDDDDDDDDDDDDD");
        body.put("argon2Params", "m=1024,t=1,p=1,v=19,hashLen=32");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register still requires email")
    void registerRejectsMissingEmail() throws Exception {
        Map<String, Object> body = clientRegisterBody(
                "noemail_" + suffix, "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE", "EEEEEEEEEEEEEEEEEEEEEE");
        body.remove("email");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Legacy login dispatch accepts plaintext and rejects clientHash")
    void loginDispatchLegacyMatrix() throws Exception {
        String username = "legacy_login_" + suffix;
        registerLegacyUser(username, "Password123!");

        loginLegacy(username, "Password123!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        Map<String, Object> clientHashBody = Map.of(
                "username", username,
                "clientHash", "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clientHashBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("PASSWORD_UPGRADE_REQUIRED"));
    }

    @Test
    @DisplayName("Client-hash login dispatch accepts clientHash and rejects plaintext")
    void loginDispatchClientMatrix() throws Exception {
        String username = "client_login_" + suffix;
        String hash = "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG";
        registerClientUser(username, hash, "GGGGGGGGGGGGGGGGGGGGGG");

        Map<String, Object> clientHashBody = Map.of("username", username, "clientHash", hash);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clientHashBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        loginLegacy(username, "Password123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CLIENT_TOO_OLD"));
    }

    @Test
    @DisplayName("Login locks username after 50 failures and returns 423")
    void loginLockoutAfter50FailuresPerUsername() throws Exception {
        String username = "lockout_" + suffix;
        registerLegacyUser(username, "Password123!");

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "10.10.0." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", username,
                                    "password", "WrongPassword!"))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.10.0.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "Password123!"))))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("Change password migrates legacy user to client scheme without invalidating current JWT")
    void changePasswordDoesNotInvalidateCurrentJwt() throws Exception {
        String username = "jwt_keep_" + suffix;
        registerLegacyUser(username, "Password123!");
        String token = loginAndToken(username, "Password123!");

        Map<String, Object> changeBody = new HashMap<>();
        changeBody.put("oldPassword", "Password123!");
        changeBody.put("newClientHash", "HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH");
        changeBody.put("newClientSalt", "HHHHHHHHHHHHHHHHHHHHHH");
        changeBody.put("newArgon2Params", PARAMS);
        mockMvc.perform(post("/api/profile/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changeBody)))
                .andExpect(status().isOk());

        User user = userRepository.findByUsername(username).orElseThrow();
        assertThat(user.getPasswordScheme()).isEqualTo("CLIENT_ARGON2_BCRYPT");

        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void registerClientUser(String username, String clientHash, String salt) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(clientRegisterBody(username, clientHash, salt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void registerLegacyUser(String username, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("email", username + "@test.com");
        body.put("password", password);
        body.put("displayName", username);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private org.springframework.test.web.servlet.ResultActions loginLegacy(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username,
                        "password", password))));
    }

    private String loginAndToken(String username, String password) throws Exception {
        MvcResult result = loginLegacy(username, password)
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        return data.get("accessToken").toString();
    }

    private Map<String, Object> clientRegisterBody(String username, String clientHash, String salt) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("email", username + "@test.com");
        body.put("displayName", username);
        body.put("clientHash", clientHash);
        body.put("clientSalt", salt);
        body.put("argon2Params", PARAMS);
        return body;
    }

    private Map<String, Object> data(MvcResult result) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) payload.get("data");
    }
}
