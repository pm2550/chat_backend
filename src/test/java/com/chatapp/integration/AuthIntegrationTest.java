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
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Login with non-existent user returns bad request")
    void testLoginNonExistentUser() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createLoginRequest("nonexistent_user_xyz", "password123"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Access protected endpoint without token returns 403")
    void testAccessProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Access protected endpoint with invalid token returns 403")
    void testAccessProtectedEndpointWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/chat-rooms")
                .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isForbidden());
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
