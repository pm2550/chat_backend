package com.chatapp.integration;

import com.chatapp.entity.FeatureCost;
import com.chatapp.entity.User;
import com.chatapp.repository.FeatureCostRepository;
import com.chatapp.repository.RedemptionCodeRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.RedemptionCodeService;
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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
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
        "rate-limit.auth-requests-per-minute=2000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Points controller integration")
class PointsControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private FeatureCostRepository featureCostRepository;
    @Autowired private RedemptionCodeRepository codeRepository;

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        featureCostRepository.save(new FeatureCost(
                "test_debit",
                1,
                10,
                true,
                "test",
                null));
        userToken = registerAndLogin("points_user_", User.Role.USER);
        adminToken = registerAndLogin("points_admin_", User.Role.ADMIN);
    }

    @Test
    @DisplayName("user points endpoints require authentication")
    void userEndpoints_requireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/points/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/v1/points/ledger"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/v1/points/preview/test_debit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/v1/points/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCD-EFGH-2345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("admin code endpoints require ADMIN role")
    void adminEndpoints_requireAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/admin/codes/issue")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count":1,"points_each":10}
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/admin/codes")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("POST /points/redeem without JWT returns 401 ApiResponse envelope")
    void redeem_withoutJwt_returns401Envelope() throws Exception {
        mockMvc.perform(post("/api/v1/points/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCD-EFGH-2345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /admin/codes/issue as non-admin returns 403 ApiResponse envelope")
    void adminIssue_asNonAdmin_returns403Envelope() throws Exception {
        mockMvc.perform(post("/api/v1/admin/codes/issue")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":1,"points_each":10}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /points/me lazy creates balance and returns free quota")
    void getMe_lazyCreatesBalance() throws Exception {
        mockMvc.perform(get("/api/v1/points/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_points").value(0))
                .andExpect(jsonPath("$.free_remaining_per_feature.test_debit").value(10));
    }

    @Test
    @DisplayName("admin issue returns plaintext once and stores only hash")
    void adminIssue_returnsPlaintextAndStoresHashOnly() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/codes/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":2,"points_each":100,"batch_label":"it","memo":"integration"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes", hasSize(2)))
                .andExpect(jsonPath("$.codes[0]",
                        matchesPattern("[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}")))
                .andReturn().getResponse().getContentAsString();

        String code = objectMapper.readTree(response).path("codes").get(0).asText();
        org.junit.jupiter.api.Assertions.assertFalse(codeRepository.existsById(code));
        org.junit.jupiter.api.Assertions.assertTrue(
                codeRepository.existsById(RedemptionCodeService.hashCode(code)));
    }

    @Test
    @DisplayName("redeem invalid format returns 400")
    void redeem_invalidFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/points/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("issue and redeem code credits user's paid balance")
    void issueAndRedeem_creditsBalance() throws Exception {
        String issueResponse = mockMvc.perform(post("/api/v1/admin/codes/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":1,"points_each":77,"batch_label":"redeem-it","memo":"integration"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(issueResponse).path("codes").get(0).asText();

        mockMvc.perform(post("/api/v1/points/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credited").value(77))
                .andExpect(jsonPath("$.new_paid_balance").value(77));

        mockMvc.perform(get("/api/v1/points/ledger?limit=5")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("redeem_code"))
                .andExpect(jsonPath("$[0].delta").value(77));
    }

    @Test
    @DisplayName("debit-test endpoint consumes free quota first")
    void debitTest_consumesFreeQuota() throws Exception {
        mockMvc.perform(post("/api/v1/points/debit-test")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used_free").value(1))
                .andExpect(jsonPath("$.used_paid").value(0));
    }

    @Test
    @DisplayName("admin credit endpoint credits a user and exposes audit ledger")
    void adminCredit_creditsUserAndLedger() throws Exception {
        User target = userRepository.findAll().stream()
                .filter(user -> user.getRoles().contains(User.Role.USER))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/api/v1/admin/users/{userId}/credit", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":15,"memo":"manual adjustment"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_points").value(15));

        mockMvc.perform(get("/api/v1/admin/users/{userId}/ledger?limit=5", target.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("admin_credit"))
                .andExpect(jsonPath("$[0].delta").value(15))
                .andExpect(jsonPath("$[0].memo").value("manual adjustment"));
    }

    @Test
    @DisplayName("duplicate redemption returns 409 body code")
    void redeem_duplicateCode_returnsConflict() throws Exception {
        String issueResponse = mockMvc.perform(post("/api/v1/admin/codes/issue")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":1,"points_each":25,"batch_label":"dup-it"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(issueResponse).path("codes").get(0).asText();

        mockMvc.perform(post("/api/v1/points/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/points/redeem")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("preview reports insufficient when free quota is exhausted and no paid points remain")
    void preview_afterFreeQuotaExhausted_reportsInsufficient() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/points/debit-test")
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/points/preview/test_debit")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.free_remaining").value(0))
                .andExpect(jsonPath("$.will_use_free").value(false))
                .andExpect(jsonPath("$.paid_points").value(0))
                .andExpect(jsonPath("$.sufficient").value(false));
    }

    private String registerAndLogin(String prefix, User.Role role) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = prefix + suffix;
        String password = "Pass1234";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "email", username + "@test.com",
                                "displayName", username))))
                .andExpect(status().isOk());
        if (role == User.Role.ADMIN) {
            User user = userRepository.findByUsername(username).orElseThrow();
            user.getRoles().add(User.Role.ADMIN);
            userRepository.save(user);
        }
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginJson).path("data").path("accessToken").asText();
    }
}
