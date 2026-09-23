package com.chatapp.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RawWebSocketHandlerStaleSessionTest {

    private WebSocketSession sessionLastSeenAt(Long lastInboundAt) {
        Map<String, Object> attributes = new HashMap<>();
        if (lastInboundAt != null) {
            attributes.put("pmchat.lastInboundAt", lastInboundAt);
        }
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    @Test
    void sessionThatPingedRecentlyIsAlive() {
        long now = 1_000_000L;
        // 客户端 30 秒 ping 一次，刚错过一次也不算断
        assertFalse(RawWebSocketHandler.isStale(sessionLastSeenAt(now - 31_000), now));
        assertFalse(RawWebSocketHandler.isStale(sessionLastSeenAt(now - RawWebSocketHandler.STALE_SESSION_MS), now));
    }

    @Test
    void sessionSilentForTwoMissedPingsIsStale() {
        long now = 1_000_000L;
        assertTrue(RawWebSocketHandler.isStale(
                sessionLastSeenAt(now - RawWebSocketHandler.STALE_SESSION_MS - 1), now));
        assertTrue(RawWebSocketHandler.isStale(sessionLastSeenAt(now - 10 * 60_000), now));
    }

    @Test
    void sessionWithoutTimestampIsNeverSweptBlindly() {
        assertFalse(RawWebSocketHandler.isStale(sessionLastSeenAt(null), 1_000_000L));
    }
}
