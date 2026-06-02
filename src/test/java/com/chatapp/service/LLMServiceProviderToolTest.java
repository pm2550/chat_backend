package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.tool.Tool;
import com.chatapp.service.tool.ToolContext;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Phase-0 provider tool-calling unlock: Ollama can now drive tools, and DashScope
 * chat is routed through an OpenAI-compatible (keyless) proxy instead of throwing.
 */
class LLMServiceProviderToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ollamaSendsToolsAndParsesObjectArgumentsToolCalls() throws Exception {
        StringBuilder capturedRequest = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedRequest.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                          {"function": {"name": "echo", "arguments": {"value": "hi"}}}
                        ]
                      },
                      "prompt_eval_count": 5,
                      "eval_count": 6
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class));
            ReflectionTestUtils.setField(service, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "ollamaModel", "kimi-k2");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OLLAMA);
            BotDto.LLMResponse response = service.chat(bot,
                    List.of(new BotDto.ChatMessage("user", "use a tool")),
                    List.of(new EchoTool()));

            // Request carried the OpenAI-style tools array.
            assertTrue(capturedRequest.toString().contains("\"tools\""));
            assertTrue(capturedRequest.toString().contains("\"echo\""));
            // tool_calls parsed: object arguments stringified, id synthesized.
            assertEquals(1, response.getToolCalls().size());
            assertEquals("echo", response.getToolCalls().get(0).getName());
            assertEquals("{\"value\":\"hi\"}", response.getToolCalls().get(0).getArgumentsJson());
            assertEquals("ollama-call-1", response.getToolCalls().get(0).getId());
            // tokens = prompt_eval_count + eval_count
            assertEquals(11, response.getTokensUsed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaSerializesPriorToolCallsAndToolResults() throws Exception {
        StringBuilder capturedRequest = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedRequest.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"message": {"role": "assistant", "content": "final answer"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class));
            ReflectionTestUtils.setField(service, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "ollamaModel", "kimi-k2");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OLLAMA);

            BotDto.ChatMessage assistantTurn = new BotDto.ChatMessage(
                    "assistant", "", null, null,
                    List.of(new BotDto.ToolCall("c1", "echo", "{\"value\":\"hi\"}")));
            BotDto.ChatMessage toolResult = new BotDto.ChatMessage(
                    "tool", "{\"value\":\"hi\"}", "c1", "echo", null);

            BotDto.LLMResponse response = service.chat(bot,
                    List.of(new BotDto.ChatMessage("user", "echo hi"), assistantTurn, toolResult),
                    List.of(new EchoTool()));

            String req = capturedRequest.toString();
            // assistant tool_calls serialized with arguments as a JSON object, not a string.
            assertTrue(req.contains("\"tool_calls\""));
            assertTrue(req.contains("\"arguments\":{\"value\":\"hi\"}"));
            // tool result carries its tool_name.
            assertTrue(req.contains("\"tool_name\":\"echo\""));
            assertEquals("final answer", response.getContent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dashscopeChatRoutesThroughProxyWithoutAuthorizationWhenKeyless() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>("__unset__");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"choices": [{"message": {"role": "assistant", "content": "你好"}}],
                     "usage": {"total_tokens": 9}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class));
            ReflectionTestUtils.setField(service, "dashscopeBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(service, "dashscopeModel", "qwen-plus");
            // dashscopeApiKey stays blank (keyless proxy)

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.DASHSCOPE);
            BotDto.LLMResponse response = service.chat(bot,
                    List.of(new BotDto.ChatMessage("user", "你好")));

            assertEquals("你好", response.getContent());
            assertEquals(9, response.getTokensUsed());
            // No Authorization header sent for the keyless proxy.
            assertNull(authHeader.get());
            assertFalse("__unset__".equals(authHeader.get()));
        } finally {
            server.stop(0);
        }
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
