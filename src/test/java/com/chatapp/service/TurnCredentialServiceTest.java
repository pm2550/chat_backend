package com.chatapp.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TurnCredentialServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

    @Test
    void generate_usesCoturnRestUsernameAndHmacSha1Credential() throws Exception {
        TurnCredentialService service = new TurnCredentialService(
                "192.9.134.169",
                3478,
                "test-secret",
                1800,
                FIXED_CLOCK);

        TurnCredentialService.IceServerConfig config = service.generate(42L);

        assertEquals(1800, config.getTtl());
        assertEquals(1_800_001_800L, config.getExpiresAt());
        assertEquals(2, config.getIceServers().size());

        TurnCredentialService.IceServer turnServer = config.getIceServers().get(1);
        assertEquals("1800001800:user-42", turnServer.getUsername());
        assertEquals(expectedHmac("1800001800:user-42", "test-secret"), turnServer.getCredential());
    }

    @Test
    void generate_returnsLiteralLocalTurnAndStunUrls() {
        TurnCredentialService service = new TurnCredentialService(
                "192.9.134.169",
                3478,
                "test-secret",
                1800,
                FIXED_CLOCK);

        TurnCredentialService.IceServerConfig config = service.generate(7L);

        assertEquals("stun:192.9.134.169:3478", config.getIceServers().get(0).getUrls().get(0));
        assertEquals("turn:192.9.134.169:3478?transport=udp", config.getIceServers().get(1).getUrls().get(0));
        assertEquals("turn:192.9.134.169:3478?transport=tcp", config.getIceServers().get(1).getUrls().get(1));
        assertNull(config.getIceServers().get(0).getUsername());
        assertNull(config.getIceServers().get(0).getCredential());
    }

    @Test
    void generate_rejectsBlankSharedSecret() {
        TurnCredentialService service = new TurnCredentialService(
                "192.9.134.169",
                3478,
                " ",
                1800,
                FIXED_CLOCK);

        assertThrows(IllegalStateException.class, () -> service.generate(1L));
    }

    private static String expectedHmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
