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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LLMServiceToolCallTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAiCompatibleProviderSendsToolsAndParsesToolCalls() throws Exception {
        StringBuilder capturedRequest = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequest.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
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
                      "usage": {"total_tokens": 11}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
            LLMService service = new LLMService(objectMapper, credentialService);
            ReflectionTestUtils.setField(service, "hermesApiKey", "test-key");
            ReflectionTestUtils.setField(service, "hermesBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(service, "hermesModel", "hermes-agent");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.HERMES);
            BotDto.LLMResponse response = service.chat(bot,
                    List.of(new BotDto.ChatMessage("user", "use a tool")),
                    List.of(new EchoTool()));

            assertTrue(capturedRequest.toString().contains("\"tools\""));
            assertTrue(capturedRequest.toString().contains("\"tool_choice\":\"auto\""));
            assertEquals(1, response.getToolCalls().size());
            assertEquals("echo", response.getToolCalls().get(0).getName());
            assertEquals("{\"value\":\"hello\"}", response.getToolCalls().get(0).getArgumentsJson());
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
