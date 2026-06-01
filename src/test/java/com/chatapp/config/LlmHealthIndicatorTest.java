package com.chatapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LlmHealthIndicatorTest {

    @Test
    void requiredLlmAllowsHermesConfiguration() {
        LlmHealthIndicator indicator = new LlmHealthIndicator();
        ReflectionTestUtils.setField(indicator, "required", true);
        ReflectionTestUtils.setField(indicator, "openaiApiKey", "");
        ReflectionTestUtils.setField(indicator, "claudeApiKey", "");
        ReflectionTestUtils.setField(indicator, "deepseekApiKey", "");
        ReflectionTestUtils.setField(indicator, "ollamaBaseUrl", "");
        ReflectionTestUtils.setField(indicator, "hermesApiKey", "test-key");
        ReflectionTestUtils.setField(indicator, "hermesBaseUrl", "http://127.0.0.1:8642/v1");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("hermesConfigured", true);
    }

    @Test
    void requiredLlmIsDownWhenNoProviderConfigured() {
        LlmHealthIndicator indicator = new LlmHealthIndicator();
        ReflectionTestUtils.setField(indicator, "required", true);
        ReflectionTestUtils.setField(indicator, "openaiApiKey", "");
        ReflectionTestUtils.setField(indicator, "claudeApiKey", "");
        ReflectionTestUtils.setField(indicator, "deepseekApiKey", "");
        ReflectionTestUtils.setField(indicator, "ollamaBaseUrl", "");
        ReflectionTestUtils.setField(indicator, "hermesApiKey", "");
        ReflectionTestUtils.setField(indicator, "hermesBaseUrl", "");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
