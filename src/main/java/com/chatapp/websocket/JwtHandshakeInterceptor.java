package com.chatapp.websocket;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import com.chatapp.service.TokenBlacklistService;
import com.chatapp.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Authenticates raw WebSocket handshakes from ?token=&lt;jwt&gt;.
 * On success the resolved User is stuffed into session attributes under
 * {@link RawWebSocketHandler#ATTR_USER}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.debug("ws handshake rejected: missing token");
            return false;
        }
        if (!jwtUtils.validateJwtToken(token)) {
            log.debug("ws handshake rejected: invalid token");
            return false;
        }
        try {
            if (tokenBlacklistService.isBlacklisted(jwtUtils.getTokenId(token))) {
                log.debug("ws handshake rejected: token blacklisted");
                return false;
            }
        } catch (Exception ex) {
            // Redis unavailable shouldn't permanently break websockets in dev
            log.warn("Blacklist check failed, allowing handshake: {}", ex.getMessage());
        }
        String username = jwtUtils.getUserNameFromJwtToken(token);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.debug("ws handshake rejected: user not found: {}", username);
            return false;
        }
        attributes.put(RawWebSocketHandler.ATTR_USER, user);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    private String extractToken(ServerHttpRequest request) {
        // Prefer Authorization header for non-browser clients
        var authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String tokenParam = servletRequest.getServletRequest().getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                return tokenParam;
            }
        }
        URI uri = request.getURI();
        String query = uri.getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
