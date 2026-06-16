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
    void imageContentRoutesToHermesProviderRegardlessOfConfiguredTextProvider() {
        HermesProvider hermesProvider = mock(HermesProvider.class);
        LLMService service = new LLMService(objectMapper, mock(ProviderCredentialService.class), hermesProvider);

        BotConfig bot = new BotConfig();
        bot.setLlmProvider(BotConfig.LLMProvider.OPENAI);
        bot.setTemperature(0.2);
        bot.setMaxTokens(128);

        BotDto.ChatMessage user = BotDto.ChatMessage.userWithImages(
                "describe", List.of(new BotDto.ImageAttachment(
                        "normal.png", "image/png", "data:image/png;base64,aGVsbG8=")));
        BotDto.LLMResponse hermesReply = new BotDto.LLMResponse();
        hermesReply.setContent("Hermes vision ok");
        hermesReply.setTokensUsed(11);
        hermesReply.setModel("grok-4.3");
        when(hermesProvider.chat(eq(bot), anyList(), anyList())).thenReturn(hermesReply);

        BotDto.LLMResponse response = service.chat(bot, List.of(user));

        assertEquals("Hermes vision ok", response.getContent());
        assertEquals(11, response.getTokensUsed());
        assertEquals("grok-4.3", response.getModel());
        verify(hermesProvider).chat(eq(bot), eq(List.of(user)), eq(List.of()));
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
