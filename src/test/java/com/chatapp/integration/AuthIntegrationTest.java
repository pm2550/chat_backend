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
import java.util.Map;
import java.util.UUID;

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
public class AuthIntegrationTest {

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

    private Map<String, Object> createRegisterRequest(String username, String email, String password) {
        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("email", email);
        request.put("password", password);
        request.put("displayName", username);
        return request;
    }

    private Map<String, Object> createLoginRequest(String username, String password) {
        Map<String, Object> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);
        return request;
    }

    private MvcResult registerUser(String username, String email, String password) throws Exception {
        Map<String, Object> registerRequest = createRegisterRequest(username, email, password);
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andReturn();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        Map<String, Object> loginRequest = createLoginRequest(username, password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        return (String) data.get("accessToken");
    }

    @Test
    @DisplayName("Full auth flow: register -> login -> get token -> access protected endpoint -> logout")
    void testFullAuthFlow() throws Exception {
        String username = "authflow_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        // Step 1: Register
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRegisterRequest(username, email, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.email").value(email));

        // Step 2: Login and get JWT token
        String accessToken = loginAndGetToken(username, password);

        // Step 3: Use token to access a protected endpoint (validate token)
        mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username));

        // Step 4: Logout
        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // Step 5: After logout, if token is blacklisted, it should be rejected
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/auth/validate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with duplicate username returns error")
    void testRegisterDuplicateUsername() throws Exception {
        String username = "duplicate_" + uniqueSuffix;
        String email1 = username + "_1@test.com";
        String email2 = username + "_2@test.com";
        String password = "password123";

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRegisterRequest(username, email1, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRegisterRequest(username, email2, password))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Register with duplicate email returns error")
    void testRegisterDuplicateEmail() throws Exception {
        String email = "dupemail_" + uniqueSuffix + "@test.com";
        String password = "password123";

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRegisterRequest("user1_" + uniqueSuffix, email, password))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRegisterRequest("user2_" + uniqueSuffix, email, password))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with wrong password returns bad request")
    void testLoginWrongPassword() throws Exception {
        String username = "wrongpwd_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        registerUser(username, email, password);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createLoginRequest(username, "wrongpassword"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login with non-existent user returns unauthorized")
    void testLoginNonExistentUser() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createLoginRequest("nonexistent_user_xyz", "password123"))))
                .andExpect(status().isUnauthorized());
    }



    @Test
    @DisplayName("Client-side hash registration returns client salt params and logs in with clientHash")
    void clientHashRegisterAndLogin() throws Exception {
        String username = "clienthash_" + uniqueSuffix;
        String email = username + "@test.com";
        String salt = "AAAAAAAAAAAAAAAAAAAAAA";
        String clientHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("username", username);
        registerRequest.put("email", email);
        registerRequest.put("clientHash", clientHash);
        registerRequest.put("clientSalt", salt);
        registerRequest.put("argon2Params", "m=65536,t=3,p=1,v=19,hashLen=32");
        registerRequest.put("displayName", username);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/auth/client-salt-params")
                .param("username", username))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.salt").value(salt))
                .andExpect(jsonPath("$.data.scheme").value("CLIENT_ARGON2_BCRYPT"));

        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("clientHash", clientHash);
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("Client-salt params are stable and non-enumerating for missing users")
    void clientSaltParamsForMissingUsersAreStable() throws Exception {
        String username = "missing_" + uniqueSuffix;
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
        Map<String, Object> a = objectMapper.readValue(first.getResponse().getContentAsString(), Map.class);
        Map<String, Object> b = objectMapper.readValue(second.getResponse().getContentAsString(), Map.class);
        Map<String, Object> dataA = (Map<String, Object>) a.get("data");
        Map<String, Object> dataB = (Map<String, Object>) b.get("data");
        org.junit.jupiter.api.Assertions.assertEquals(dataA.get("salt"), dataB.get("salt"));
    }

    @Test
    @DisplayName("Client-hash account rejects plaintext old clients")
    void clientHashAccountRejectsPlaintextLogin() throws Exception {
        String username = "clientold_" + uniqueSuffix;
        String email = username + "@test.com";
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("username", username);
        registerRequest.put("email", email);
        registerRequest.put("clientHash", "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        registerRequest.put("clientSalt", "BBBBBBBBBBBBBBBBBBBBBB");
        registerRequest.put("argon2Params", "m=65536,t=3,p=1,v=19,hashLen=32");
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createLoginRequest(username, "plaintext"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CLIENT_TOO_OLD"));
    }


    @Test
    @DisplayName("Legacy account can change password into client-hash scheme")
    void legacyAccountCanUpgradePasswordOnChange() throws Exception {
        String username = "upgrade_" + uniqueSuffix;
        String email = username + "@test.com";
        String oldPassword = "password123";
        String newClientHash = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        String newSalt = "CCCCCCCCCCCCCCCCCCCCCC";

        registerUser(username, email, oldPassword);
        String token = loginAndGetToken(username, oldPassword);

        Map<String, Object> changeRequest = new HashMap<>();
        changeRequest.put("oldPassword", oldPassword);
        changeRequest.put("newClientHash", newClientHash);
        changeRequest.put("newClientSalt", newSalt);
        changeRequest.put("newArgon2Params", "m=65536,t=3,p=1,v=19,hashLen=32");
        mockMvc.perform(post("/api/profile/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/client-salt-params")
                .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheme").value("CLIENT_ARGON2_BCRYPT"))
                .andExpect(jsonPath("$.data.salt").value(newSalt));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createLoginRequest(username, oldPassword))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CLIENT_TOO_OLD"));

        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("clientHash", newClientHash);
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }


    @Test
    @DisplayName("Access protected endpoint without token returns 401")
    void testAccessProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("Access protected endpoint with invalid token returns 401")
    void testAccessProtectedEndpointWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms")
                .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("Check username availability")
    void testCheckUsernameAvailability() throws Exception {
        String username = "checkuser_" + uniqueSuffix;
        String email = username + "@test.com";

        mockMvc.perform(get("/api/auth/check-username")
                .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        registerUser(username, email, "password123");

        mockMvc.perform(get("/api/auth/check-username")
                .param("username", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    @DisplayName("Register with invalid data returns bad request")
    void testRegisterWithInvalidData() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("username", "");
        request.put("email", "test@test.com");
        request.put("password", "password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Token refresh flow")
    void testTokenRefreshFlow() throws Exception {
        String username = "refresh_" + uniqueSuffix;
        String email = username + "@test.com";
        String password = "password123";

        registerUser(username, email, password);

        Map<String, Object> loginRequest = createLoginRequest(username, password);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> data = (Map<String, Object>) responseMap.get("data");
        String refreshToken = (String) data.get("refreshToken");

        mockMvc.perform(post("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
