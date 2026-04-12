package com.chatapp.config;

import com.chatapp.websocket.JwtHandshakeInterceptor;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration.
 *
 * Two wire protocols are exposed:
 *   - STOMP over SockJS at {@code /ws} (legacy)
 *   - Raw JSON at {@code /api/ws} — what the Flutter client actually speaks
 *
 * The Flutter client connects with {@code ws://host/api/ws?token=JWT}.
 */
@Configuration
@EnableWebSocket
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private final RawWebSocketHandler rawWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Value("${websocket.allowed-origins:*}")
    private String allowedOrigins;

    @Value("${websocket.endpoint:/ws}")
    private String endpoint;

    @Value("${websocket.raw-endpoint:/api/ws}")
    private String rawEndpoint;

    // --- STOMP broker (legacy) ---

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(endpoint)
                .setAllowedOriginPatterns(allowedOrigins.split(","))
                .withSockJS();
        registry.addEndpoint(endpoint)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    // --- Raw JSON handler (primary path for Flutter client) ---

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(rawWebSocketHandler, rawEndpoint)
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
