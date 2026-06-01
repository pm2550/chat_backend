package com.chatapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsValidatorTest {

    @Test
    void prodBlocksStartupWhenModelAccessIsRequiredButMissing() {
        SecretsValidator validator = validProdValidator();
        ReflectionTestUtils.setField(validator, "llmRequired", true);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_REQUIRED=true");
    }

    @Test
    void prodAllowsGatewayAsModelAccessProvider() {
        SecretsValidator validator = validProdValidator();
        ReflectionTestUtils.setField(validator, "llmRequired", true);
        ReflectionTestUtils.setField(validator, "agentGatewayEnabled", true);
        ReflectionTestUtils.setField(validator, "agentGatewayBaseUrl", "https://gateway.example.com");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private SecretsValidator validProdValidator() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        SecretsValidator validator = new SecretsValidator(environment);
        ReflectionTestUtils.setField(validator, "jwtSecret",
                Base64.getEncoder().encodeToString("0123456789012345678901234567890123456789012345678901234567890123".getBytes()));
        ReflectionTestUtils.setField(validator, "dbPassword", "strong-db-password");
        ReflectionTestUtils.setField(validator, "corsOrigins", "https://chat.example.com");
        ReflectionTestUtils.setField(validator, "wsOrigins", "https://chat.example.com");
        ReflectionTestUtils.setField(validator, "fileUploadDir", "/var/lib/chat/uploads");
        ReflectionTestUtils.setField(validator, "openaiApiKey", "");
        ReflectionTestUtils.setField(validator, "claudeApiKey", "");
        ReflectionTestUtils.setField(validator, "deepseekApiKey", "");
        ReflectionTestUtils.setField(validator, "ollamaBaseUrl", "");
        ReflectionTestUtils.setField(validator, "agentGatewayEnabled", false);
        ReflectionTestUtils.setField(validator, "agentGatewayBaseUrl", "");
        return validator;
    }
}
