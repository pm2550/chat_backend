package com.chatapp.controller;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.service.BotGatewayService;
import com.chatapp.service.BotRateLimitService;
import com.chatapp.service.BotTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotGatewayControllerTest {

    @Mock private BotTokenService botTokenService;
    @Mock private BotGatewayService botGatewayService;
    @Mock private BotRateLimitService rateLimitService;

    @InjectMocks private BotGatewayController controller;

    private BotConfig bot;

    @BeforeEach
    void setUp() {
        bot = new BotConfig();
        bot.setId(5L);
    }

    @Test
    void postMessageWithoutTokenIsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.postMessage(null, Map.of("roomId", 1, "content", "hi")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void postMessageWithInvalidTokenIsUnauthorized() {
        when(botTokenService.resolveBotByToken("bad")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.postMessage("Bearer bad", Map.of("roomId", 1, "content", "hi")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void postMessageWithValidTokenPosts() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(99L);
        when(botGatewayService.postAsBot(eq(bot), eq(1L), eq("hi"))).thenReturn(m);

        var response = controller.postMessage("Bearer pmcb_ok", Map.of("roomId", 1, "content", "hi"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(99L, response.getBody().getData().get("messageId"));
    }

    @Test
    void postMessageRateLimitedIsTooManyRequests() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.postMessage("Bearer pmcb_ok", Map.of("roomId", 1, "content", "hi")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }
}
