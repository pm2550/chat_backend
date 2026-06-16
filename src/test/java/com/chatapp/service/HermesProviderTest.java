package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.tool.Tool;
import com.chatapp.service.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatWithImageMessagePostsOpenAiMultimodalBody() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>("");
        AtomicReference<String> capturedToken = new AtomicReference<>("");
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Hermes-Token"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "model": "grok-4.3",
                      "choices": [{"message": {"role": "assistant", "content": "I see a normal image."}}],
                      "usage": {"total_tokens": 17}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HermesProvider provider = new HermesProvider(objectMapper, "test-hermes-token");
            ReflectionTestUtils.setField(provider, "chatUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            ReflectionTestUtils.setField(provider, "visionModel", "grok-4.3");

            BotConfig bot = new BotConfig();
            bot.setTemperature(0.3);
            bot.setMaxTokens(256);
            BotDto.ChatMessage imageMessage = BotDto.ChatMessage.userWithImages(
                    "what is this",
                    List.of(new BotDto.ImageAttachment("normal.png", "image/png", "data:image/png;base64,aGVsbG8=")));

            BotDto.LLMResponse response = provider.chat(bot, List.of(imageMessage), List.of());

            assertEquals("/chat", capturedPath.get());
            assertEquals("test-hermes-token", capturedToken.get());
            assertTrue(capturedBody.get().contains("\"model\":\"grok-4.3\""));
            assertTrue(capturedBody.get().contains("\"image_url\""));
            assertTrue(capturedBody.get().contains("data:image/png;base64,aGVsbG8="));
            assertEquals("I see a normal image.", response.getContent());
            assertEquals(17, response.getTokensUsed());
            assertEquals("grok-4.3", response.getModel());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatWithToolsPostsDefinitionsAndParsesToolCalls() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "model": "grok-4.3",
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "",
                          "tool_calls": [{
                            "id": "call-1",
                            "type": "function",
                            "function": {"name": "echo", "arguments": "{\\"value\\":\\"hello\\"}"}
                          }]
                        }
                      }],
                      "usage": {"total_tokens": 19}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HermesProvider provider = new HermesProvider(objectMapper, "test-hermes-token");
            ReflectionTestUtils.setField(provider, "chatUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");

            BotConfig bot = new BotConfig();
            bot.setModelName("grok-4.3");
            bot.setTemperature(0.4);
            bot.setMaxTokens(128);

            BotDto.LLMResponse response = provider.chat(
                    bot,
                    List.of(new BotDto.ChatMessage("user", "use a tool")),
                    List.of(new EchoTool()));

            assertTrue(capturedBody.get().contains("\"model\":\"grok-4.3\""));
            assertTrue(capturedBody.get().contains("\"tools\""));
            assertTrue(capturedBody.get().contains("\"tool_choice\":\"auto\""));
            assertEquals(1, response.getToolCalls().size());
            assertEquals("echo", response.getToolCalls().get(0).getName());
            assertEquals("{\"value\":\"hello\"}", response.getToolCalls().get(0).getArgumentsJson());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hermesHttpErrorPropagatesDetail() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] body = "{\"error\":\"provider down\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HermesProvider provider = new HermesProvider(objectMapper, "test-hermes-token");
            ReflectionTestUtils.setField(provider, "chatUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat");

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    provider.chat(new BotConfig(), List.of(new BotDto.ChatMessage("user", "hi")), List.of()));

            assertTrue(thrown.getMessage().contains("Hermes /chat failed: HTTP 502"));
            assertTrue(thrown.getMessage().contains("provider down"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingInternalTokenFailsFast() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new HermesProvider(objectMapper, "  "));

        assertTrue(thrown.getMessage().contains("HERMES_INTERNAL_TOKEN"));
    }

    private class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo values";
        }

        @Override
        public JsonNode parametersSchema() {
            return objectMapper.createObjectNode()
                    .put("type", "object")
                    .set("properties", objectMapper.createObjectNode()
                            .set("value", objectMapper.createObjectNode().put("type", "string")));
        }

        @Override
        public JsonNode execute(JsonNode params, ToolContext context) {
            return objectMapper.createObjectNode().put("value", params.path("value").asText());
        }
    }

}
