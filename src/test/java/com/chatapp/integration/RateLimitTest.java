package com.chatapp.integration;

import com.chatapp.service.CloudStorageService;
import com.chatapp.service.LLMService;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.TokenBlacklistService;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Dedicated rate-limit test with its own Spring context so its aggressive limits
 * (3 auth req/min) do not interfere with other integration tests.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.main.allow-circular-references=true",
        "spring.main.allow-bean-definition-overriding=true",
        "server.servlet.context-path=",
        "rate-limit.requests-per-minute=10",
        "rate-limit.auth-requests-per-minute=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Rate limit enforcement")
class RateLimitTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private LLMService llmService;
    @MockBean private CloudStorageService cloudStorageService;

    @Test
    @DisplayName("Auth endpoint returns 429 after exceeding per-minute limit")
    void auth_rate_limit_triggers() throws Exception {
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        int blocked = 0;
        int attempts = 12;
        for (int i = 0; i < attempts; i++) {
            int code = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                    .andReturn().getResponse().getStatus();
            if (code == 429) blocked++;
        }
        assertTrue(blocked >= attempts - 3,
                "expected at least " + (attempts - 3) + " rejections, saw " + blocked);
    }
}
