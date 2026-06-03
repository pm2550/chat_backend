package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
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
    void ollamaCloudWithApiKeyUsesOpenAiCompatibleEndpointAndBearerAuth() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>("__unset__");
        StringBuilder capturedRequest = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedRequest.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "choices": [{
                        "finish_reason": "tool_calls",
                        "message": {
                          "role": "assistant",
                          "content": "",
                          "tool_calls": [{
                            "id": "call-ollama-cloud",
                            "type": "function",
                            "function": {"name": "echo", "arguments": "{\\"value\\":\\"cloud\\"}"}
                          }]
                        }
                      }],
                      "usage": {"total_tokens": 13}
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
            ReflectionTestUtils.setField(service, "ollamaModel", "kimi-k2.6");
            ReflectionTestUtils.setField(service, "ollamaApiKey", "cloud-key");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OLLAMA);
            BotDto.LLMResponse response = service.chat(bot,
                    List.of(new BotDto.ChatMessage("user", "use a tool")),
                    List.of(new EchoTool()));

            assertEquals("/v1/chat/completions", capturedPath.get());
            assertEquals("Bearer cloud-key", authHeader.get());
            assertTrue(capturedRequest.toString().contains("\"tools\""));
            assertTrue(capturedRequest.toString().contains("\"tool_choice\":\"auto\""));
            assertEquals(1, response.getToolCalls().size());
            assertEquals("echo", response.getToolCalls().get(0).getName());
            assertEquals("{\"value\":\"cloud\"}", response.getToolCalls().get(0).getArgumentsJson());
            assertEquals(13, response.getTokensUsed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaSendsToolsAndParsesObjectArgumentsToolCalls() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>("__unset__");
        StringBuilder capturedRequest = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
            assertEquals("/api/chat", capturedPath.get());
            assertNull(authHeader.get());
            assertFalse("__unset__".equals(authHeader.get()));
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

    @Test
    void credentialBaseUrlOverridesServerDefault() throws Exception {
        StringBuilder captured = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.append("hit");
            byte[] body = """
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class));
            // Server default endpoint is intentionally unreachable; the credential base_url must win.
            ReflectionTestUtils.setField(service, "openaiApiKey", "default-key");
            ReflectionTestUtils.setField(service, "openaiBaseUrl", "http://127.0.0.1:1/should-not-be-used");

            ProviderCredential cred = new ProviderCredential();
            cred.setIsActive(true);
            cred.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
            bot.setProviderCredential(cred);

            BotDto.LLMResponse response = service.chat(bot, List.of(new BotDto.ChatMessage("user", "hi")));

            assertEquals("ok", response.getContent());
            assertEquals("hit", captured.toString());
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
