package com.chatapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void ollamaCloudHealthUsesModelsEndpointWithBearerAuth() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LlmHealthIndicator indicator = new LlmHealthIndicator();
            ReflectionTestUtils.setField(indicator, "required", true);
            ReflectionTestUtils.setField(indicator, "openaiApiKey", "");
            ReflectionTestUtils.setField(indicator, "claudeApiKey", "");
            ReflectionTestUtils.setField(indicator, "deepseekApiKey", "");
            ReflectionTestUtils.setField(indicator, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(indicator, "ollamaApiKey", "ollama-cloud-key");
            ReflectionTestUtils.setField(indicator, "hermesApiKey", "");
            ReflectionTestUtils.setField(indicator, "hermesBaseUrl", "");

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            assertThat(path.get()).isEqualTo("/v1/models");
            assertThat(auth.get()).isEqualTo("Bearer ollama-cloud-key");
        } finally {
            server.stop(0);
        }
    }
}
