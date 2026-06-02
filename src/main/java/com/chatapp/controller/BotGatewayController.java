package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.service.BotGatewayService;
import com.chatapp.service.BotRateLimitService;
import com.chatapp.service.BotTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

/**
 * Inbound bot gateway (Phase 4 / F1) — the Telegram/Feishu-style "external service
 * posts AS a bot" surface. Authenticated by a per-bot token (NOT a user JWT); the
 * path is permitAll in SecurityConfig and the token is resolved here per request.
 */
@RestController
@RequestMapping("/api/bot-gateway/v1")
@RequiredArgsConstructor
public class BotGatewayController {

    private static final int INBOUND_PER_MINUTE = 60;

    private final BotTokenService botTokenService;
    private final BotGatewayService botGatewayService;
    private final BotRateLimitService rateLimitService;

    /** External bot posts a message into a bound room (Telegram sendMessage equivalent). */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> postMessage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization);
        Long roomId = parseRoomId(body.get("roomId"));
        String content = body.get("content") != null ? body.get("content").toString() : null;
        Message message = botGatewayService.postAsBot(bot, roomId, content);
        return ResponseEntity.ok(ApiResponse.success("sent", Map.<String, Object>of(
                "messageId", message.getId(),
                "chatRoomId", roomId,
                "status", "SENT")));
    }

    /** Rooms this bot token is bound to (where it may post). */
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rooms(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        BotConfig bot = authenticate(authorization);
        return ResponseEntity.ok(ApiResponse.success(Map.<String, Object>of(
                "botId", bot.getId(),
                "rooms", botGatewayService.boundRoomIds(bot))));
    }

    private BotConfig authenticate(String authorization) {
        String token = extractToken(authorization);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bot token");
        }
        BotConfig bot = botTokenService.resolveBotByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid bot token"));
        if (!rateLimitService.tryAcquire("botgw:" + bot.getId(), INBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "bot gateway rate limit exceeded");
        }
        return bot;
    }

    private String extractToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private Long parseRoomId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId required");
    }
}
