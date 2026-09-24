package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Bot 编辑器的 Temperature 以前对 Claude 完全不生效；现在对支持采样参数的型号生效。 */
class LLMServiceClaudeTemperatureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void claudeModelsThatAcceptSamplingGetTheBotTemperature() throws Exception {
        JsonNode request = callClaude("claude-sonnet-4-20250514", 0.3);
        assertEquals(0.3, request.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void claudeTemperatureIsClampedToItsZeroToOneRange() throws Exception {
        JsonNode request = callClaude("claude-3-5-sonnet-latest", 1.6);
        assertEquals(1.0, request.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void newerClaudeModelsThatRejectSamplingGetNoTemperature() throws Exception {
        JsonNode request = callClaude("claude-opus-4-8", 0.3);
        assertFalse(request.has("temperature"));
    }

    @Test
    void temperatureSupportFollowsModelGeneration() {
        assertTrue(LLMService.claudeAcceptsTemperature("claude-3-opus-20240229"));
        assertTrue(LLMService.claudeAcceptsTemperature("claude-sonnet-4-5"));
        assertTrue(LLMService.claudeAcceptsTemperature("claude-opus-4-6"));
        assertTrue(LLMService.claudeAcceptsTemperature("claude-haiku-4-5-20251001"));
        assertTrue(LLMService.claudeAcceptsTemperature("anthropic/claude-sonnet-4-6"));
        assertFalse(LLMService.claudeAcceptsTemperature("claude-opus-4-7"));
        assertFalse(LLMService.claudeAcceptsTemperature("claude-opus-5"));
        assertFalse(LLMService.claudeAcceptsTemperature("claude-sonnet-5"));
        assertFalse(LLMService.claudeAcceptsTemperature("claude-fable-5-1"));
        assertFalse(LLMService.claudeAcceptsTemperature(""));
    }

    private JsonNode callClaude(String model, double temperature) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class));
            ReflectionTestUtils.setField(service, "claudeBaseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(service, "claudeApiKey", "test-key");
            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.CLAUDE);
            bot.setModelName(model);
            bot.setTemperature(temperature);
            BotDto.LLMResponse response = service.chat(bot, List.of(new BotDto.ChatMessage("user", "hi")));
            assertEquals("ok", response.getContent());
            return objectMapper.readTree(captured.get());
        } finally {
            server.stop(0);
        }
    }
}
