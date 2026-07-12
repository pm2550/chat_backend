package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.BotConfig;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMServiceVisionRoutingTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAiImageContentStaysWithConfiguredOpenAiProvider() throws Exception {
        HermesProvider hermesProvider = mock(HermesProvider.class);
        AtomicReference<String> captured = new AtomicReference<>("");
        HttpServer server = server(captured, "OpenAI vision ok", 11);
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class), hermesProvider);
            ReflectionTestUtils.setField(service, "openaiApiKey", "openai-vision-key");
            ReflectionTestUtils.setField(service, "openaiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(service, "openaiModel", "gpt-vision-test");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
            bot.setTemperature(0.2);
            bot.setMaxTokens(128);

            BotDto.ChatMessage user = BotDto.ChatMessage.userWithImages(
                    "describe", List.of(new BotDto.ImageAttachment(
                            "normal.png", "image/png", "data:image/png;base64,aGVsbG8=")));
            BotDto.LLMResponse response = service.chat(bot, List.of(user));

            assertEquals("OpenAI vision ok", response.getContent());
            assertEquals(11, response.getTokensUsed());
            assertEquals("gpt-vision-test", response.getModel());
            var request = objectMapper.readTree(captured.get());
            var content = request.path("messages").path(0).path("content");
            assertEquals(2, content.size());
            assertEquals("text", content.path(0).path("type").asText());
            assertEquals("describe", content.path(0).path("text").asText());
            assertFalse(content.path(0).has("image_url"));
            assertEquals("image_url", content.path(1).path("type").asText());
            assertEquals("data:image/png;base64,aGVsbG8=",
                    content.path(1).path("image_url").path("url").asText());
            assertFalse(content.path(1).has("text"));
            verify(hermesProvider, never()).chat(eq(bot), anyList(), anyList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hermesGrokTextRoutesToHermesProvider() {
        HermesProvider hermesProvider = mock(HermesProvider.class);
        LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class), hermesProvider);

        BotConfig bot = new BotConfig();
        bot.setLlmProvider(BotConfig.LLMProvider.HERMES);
        bot.setModelName("grok-4.3");
        bot.setTemperature(0.2);
        bot.setMaxTokens(128);

        BotDto.ChatMessage user = new BotDto.ChatMessage("user", "hello");
        BotDto.LLMResponse hermesReply = new BotDto.LLMResponse();
        hermesReply.setContent("Grok text ok");
        hermesReply.setTokensUsed(7);
        hermesReply.setModel("grok-4.3");
        when(hermesProvider.chat(eq(bot), anyList(), anyList())).thenReturn(hermesReply);

        BotDto.LLMResponse response = service.chat(bot, List.of(user));

        assertEquals("Grok text ok", response.getContent());
        assertEquals("grok-4.3", response.getModel());
        verify(hermesProvider).chat(eq(bot), eq(List.of(user)), eq(List.of()));
    }

    @Test
    void hermesGrokImageContentStaysWithHermesProvider() {
        HermesProvider hermesProvider = mock(HermesProvider.class);
        LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class), hermesProvider);
        BotConfig bot = new BotConfig();
        bot.setLlmProvider(BotConfig.LLMProvider.HERMES);
        bot.setModelName("grok-4.3");
        BotDto.ChatMessage user = BotDto.ChatMessage.userWithImages(
                "read the code", List.of(new BotDto.ImageAttachment(
                        "code.png", "image/png", "data:image/png;base64,Y29kZQ==")));
        BotDto.LLMResponse reply = new BotDto.LLMResponse("7314", 9, "grok-4.3");
        when(hermesProvider.chat(eq(bot), anyList(), anyList())).thenReturn(reply);

        BotDto.LLMResponse response = service.chat(bot, List.of(user));

        assertEquals("7314", response.getContent());
        verify(hermesProvider).chat(eq(bot), eq(List.of(user)), eq(List.of()));
    }

    @Test
    void ollamaTextOnlyDoesNotUseHermesProvider() throws Exception {
        HermesProvider hermesProvider = mock(HermesProvider.class);
        AtomicReference<String> captured = new AtomicReference<>("");
        HttpServer server = server(captured, "text ok", 5);
        server.start();
        try {
            LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class), hermesProvider);
            ReflectionTestUtils.setField(service, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "ollamaApiKey", "text-key");
            ReflectionTestUtils.setField(service, "ollamaModel", "plain-text-model");

            BotConfig bot = new BotConfig();
            bot.setLlmProvider(BotConfig.LLMProvider.OLLAMA);
            bot.setTemperature(0.2);
            bot.setMaxTokens(128);

            BotDto.LLMResponse response = service.chat(bot, List.of(new BotDto.ChatMessage("user", "hello")));

            assertEquals("text ok", response.getContent());
            assertTrue(captured.get().contains("\"model\":\"plain-text-model\""));
            assertFalse(captured.get().contains("grok-4.3"));
            verify(hermesProvider, never()).chat(eq(bot), anyList(), anyList());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> captured, String reply, int tokens) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + reply
                    + "\"}}],\"usage\":{\"total_tokens\":" + tokens + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
