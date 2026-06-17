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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_SEND)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(99L);
        when(botGatewayService.postAsBot(eq(bot), eq(1L), eq("hi"), eq(null), eq(null))).thenReturn(m);

        var response = controller.postMessage("Bearer pmcb_ok", Map.of("roomId", 1, "content", "hi"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(99L, response.getBody().getData().get("messageId"));
    }

    @Test
    void postMessageAcceptsChatRoomIdAlias() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_SEND)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(100L);
        when(botGatewayService.postAsBot(eq(bot), eq(2L), eq("hi"), eq(null), eq(null))).thenReturn(m);

        var response = controller.postMessage("Bearer pmcb_ok", Map.of("chatRoomId", 2, "content", "hi"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(100L, response.getBody().getData().get("messageId"));
        verify(botGatewayService).postAsBot(eq(bot), eq(2L), eq("hi"), eq(null), eq(null));
    }

    @Test
    void postMessagePassesReplyAndFileIds() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_SEND)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(101L);
        m.setFileUrl("/api/files/chat/a.png");
        when(botGatewayService.postAsBot(eq(bot), eq(1L), eq("see this"), eq(77L), eq(88L))).thenReturn(m);

        var response = controller.postMessage("Bearer pmcb_ok", Map.of(
                "roomId", 1,
                "content", "see this",
                "reply_to_message_id", 77,
                "file_id", 88));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(101L, response.getBody().getData().get("messageId"));
        assertEquals(101L, response.getBody().getData().get("fileId"));
    }

    @Test
    void roomMessagesRequiresReadScope() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_READ)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.roomMessages("Bearer pmcb_ok", 1L, 0, 20));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void roomMessagesReturnsBoundRoomHistoryWhenReadScopePresent() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_READ)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(11L);
        m.setContent("hello");
        when(botGatewayService.listMessages(eq(bot), eq(1L), any())).thenReturn(new PageImpl<>(List.of(m)));

        var response = controller.roomMessages("Bearer pmcb_ok", 1L, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().getData().get("totalElements"));
        verify(botGatewayService).listMessages(eq(bot), eq(1L), any());
    }

    @Test
    void searchMessagesAcceptsQAlias() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_READ)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(true);
        Message m = new Message();
        m.setId(12L);
        m.setContent("gateway smoke message");
        when(botGatewayService.searchMessages(eq(bot), eq(1L), eq("smoke"), any()))
                .thenReturn(new PageImpl<>(List.of(m)));

        var response = controller.searchMessages("Bearer pmcb_ok", 1L, null, "smoke", 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("smoke", response.getBody().getData().get("keyword"));
        assertEquals(1L, response.getBody().getData().get("totalElements"));
        verify(botGatewayService).searchMessages(eq(bot), eq(1L), eq("smoke"), any());
    }

    @Test
    void postMessageRateLimitedIsTooManyRequests() {
        when(botTokenService.resolveBotByToken("pmcb_ok")).thenReturn(Optional.of(bot));
        when(botTokenService.hasScope(bot, BotTokenService.SCOPE_MESSAGE_SEND)).thenReturn(true);
        when(rateLimitService.tryAcquire(eq("botgw:5"), anyLong(), any(Duration.class))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.postMessage("Bearer pmcb_ok", Map.of("roomId", 1, "content", "hi")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }
}
