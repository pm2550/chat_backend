package com.chatapp.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class TurnCredentialService {

    private static final long DEFAULT_TTL_SECONDS = 1800;
    private static final long MIN_TTL_SECONDS = 600;

    private final String serverHost;
    private final int serverPort;
    private final String sharedSecret;
    private final long credentialTtlSeconds;
    private final Clock clock;

    @Autowired
    public TurnCredentialService(
            @Value("${turn.server-host:192.9.134.169}") String serverHost,
            @Value("${turn.server-port:3478}") int serverPort,
            @Value("${turn.shared-secret:}") String sharedSecret,
            @Value("${turn.credential-ttl-seconds:1800}") long credentialTtlSeconds) {
        this(serverHost, serverPort, sharedSecret, credentialTtlSeconds, Clock.systemUTC());
    }

    TurnCredentialService(
            String serverHost,
            int serverPort,
            String sharedSecret,
            long credentialTtlSeconds,
            Clock clock) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.sharedSecret = sharedSecret;
        this.credentialTtlSeconds = credentialTtlSeconds;
        this.clock = clock;
    }

    public IceServerConfig generate(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalStateException("TURN_STATIC_AUTH_SECRET is not configured");
        }

        long ttl = normalizedTtl();
        long expiresAt = Instant.now(clock).getEpochSecond() + ttl;
        String username = expiresAt + ":user-" + userId;
        String credential = hmacSha1(username, sharedSecret);

        String stunUrl = "stun:" + serverHost + ":" + serverPort;
        String turnUdpUrl = "turn:" + serverHost + ":" + serverPort + "?transport=udp";
        String turnTcpUrl = "turn:" + serverHost + ":" + serverPort + "?transport=tcp";

        return new IceServerConfig(List.of(
                new IceServer(List.of(stunUrl), null, null),
                new IceServer(List.of(turnUdpUrl, turnTcpUrl), username, credential)
        ), ttl, expiresAt);
    }

    private long normalizedTtl() {
        if (credentialTtlSeconds <= 0) {
            return DEFAULT_TTL_SECONDS;
        }
        return Math.max(MIN_TTL_SECONDS, credentialTtlSeconds);
    }

    private String hmacSha1(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign TURN credential", e);
        }
    }

    @Data
    @AllArgsConstructor
    public static class IceServerConfig {
        private List<IceServer> iceServers;
        private long ttl;
        private long expiresAt;
    }

    @Data
    @AllArgsConstructor
    public static class IceServer {
        private List<String> urls;
        private String username;
        private String credential;
    }
}
