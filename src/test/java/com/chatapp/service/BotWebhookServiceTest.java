package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.BotWebhookSubscription;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.BotWebhookSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotWebhookServiceTest {

    @Mock private BotWebhookSubscriptionRepository subscriptionRepository;
    @Mock private BotConfigRepository botConfigRepository;

    // Real collaborators (no external deps): SSRF policy + crypto + json.
    private final OutboundUrlPolicy outboundUrlPolicy = new OutboundUrlPolicy();
    private final CredentialCryptoService crypto =
            new CredentialCryptoService("test-master-key-material-32-bytes-long");
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BotWebhookService service;
    private User owner;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        service = new BotWebhookService(subscriptionRepository, botConfigRepository,
                outboundUrlPolicy, crypto, objectMapper);
        owner = new User();
        owner.setId(1L);
        bot = new BotConfig();
        bot.setId(5L);
        bot.setCreatedBy(owner);
    }

    @Test
    void registerRejectsInternalCallbackUrlAsSsrf() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(5L, 1L, "http://10.0.0.5/hook", "sek", "message", null));
    }

    @Test
    void registerAcceptsPublicHttpsCallback() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        when(subscriptionRepository.save(any(BotWebhookSubscription.class))).thenAnswer(inv -> {
            BotWebhookSubscription s = inv.getArgument(0);
            s.setId(70L);
            return s;
        });
        // literal public IP avoids DNS in tests
        BotWebhookService.WebhookView view =
                service.register(5L, 1L, "https://8.8.8.8/hook", "shh", "message", null);
        assertEquals("https://8.8.8.8/hook", view.callbackUrl());
        assertTrue(view.hasSecret());
    }

    @Test
    void registerRejectsNonOwner() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.register(5L, 999L, "https://8.8.8.8/hook", null, null, null));
    }

    @Test
    void dispatchReturnsTrueWhenSubscribed() {
        BotWebhookSubscription sub = new BotWebhookSubscription();
        sub.setId(70L);
        sub.setBotConfig(bot);
        sub.setChatRoomId(null); // all rooms
        sub.setCallbackUrl("https://8.8.8.8/hook");
        sub.setIsActive(true);
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(sub));
        // async deliver re-fetches by id; unstubbed findById returns Optional.empty() by
        // default, so deliver no-ops (no real network) — no explicit stub needed.

        assertTrue(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }

    @Test
    void dispatchReturnsFalseWhenNoSubscriptions() {
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of());
        assertFalse(service.dispatchIfSubscribed(bot, 100L, "hi", 1L));
    }

    // ---- delivery: HMAC signing + encrypt/decrypt round-trip (real local HTTP server) ----

    @Test
    void signsDeliveryWithHmacOverDecryptedSecret() throws Exception {
        try (CapturingServer srv = new CapturingServer(200, null)) {
            BotWebhookSubscription sub = activeSub(70L, srv.url());
            sub.setSecretEncrypted(crypto.encrypt("mysecret")); // stored encrypted at rest
            CountDownLatch delivered = new CountDownLatch(1);
            stubFor(sub, s -> delivered.countDown()); // save() runs at the end of recordDelivery

            assertTrue(service.dispatchIfSubscribed(bot, 100L, "hi there", 1L));
            // Await the terminal save() (not just server receipt) so the async delivery has
            // fully completed before we assert / Mockito tears down strict stubs.
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "webhook should be delivered");

            assertNotNull(srv.body);
            assertNotNull(srv.signature);
            assertNotNull(srv.timestamp);
            // Recompute the signature with the PLAINTEXT secret + the ts/body the server
            // actually received. A match proves the HMAC is correct AND that the secret
            // survived the encrypt->store->decrypt round-trip (a broken round-trip would
            // sign with garbage and never match the plaintext recomputation).
            String expected = "sha256=" + hmac("mysecret", srv.timestamp + "." + srv.body);
            assertEquals(expected, srv.signature);
            assertTrue(srv.body.contains("\"content\":\"hi there\""));
            assertTrue(srv.body.contains("\"botId\":5"));
        }
    }

    @Test
    void autoDisablesAfterMaxConsecutiveFailures() throws Exception {
        try (CapturingServer srv = new CapturingServer(500, null)) { // always fails
            BotWebhookSubscription sub = activeSub(71L, srv.url());
            sub.setConsecutiveFailures(19); // one more failure trips the breaker (MAX=20)
            CountDownLatch disabled = new CountDownLatch(1);
            stubFor(sub, s -> {
                if (Boolean.FALSE.equals(s.getIsActive())) disabled.countDown();
            });

            service.dispatchIfSubscribed(bot, 100L, "hi", 1L);
            // countDown -> await establishes happens-before, so reading sub here is safe.
            assertTrue(disabled.await(5, TimeUnit.SECONDS), "sub must auto-disable after 20 failures");
            assertEquals(20, sub.getConsecutiveFailures());
            assertFalse(Boolean.TRUE.equals(sub.getIsActive()));
        }
    }

    @Test
    void doesNotFollowRedirectToInternalHost() throws Exception {
        // A malicious endpoint 302-redirects to the cloud metadata service. With
        // followRedirects(false) OkHttp records the 302 verbatim and never connects to it.
        try (CapturingServer srv =
                     new CapturingServer(302, "http://169.254.169.254/latest/meta-data")) {
            BotWebhookSubscription sub = activeSub(72L, srv.url());
            CountDownLatch saved = new CountDownLatch(1);
            stubFor(sub, s -> saved.countDown());

            service.dispatchIfSubscribed(bot, 100L, "hi", 1L);
            assertTrue(saved.await(5, TimeUnit.SECONDS));
            assertEquals(302, sub.getLastDeliveryStatus());
            assertEquals(1, sub.getConsecutiveFailures()); // a 302 is not "successful"
        }
    }

    // ---- DNS-rebinding guard (the IP we connect to is re-validated at lookup time) ----

    @Test
    void guardedLookupRejectsInternalAddresses() {
        assertThrows(UnknownHostException.class,
                () -> BotWebhookService.guardedLookup(outboundUrlPolicy, "10.0.0.5"));
        assertThrows(UnknownHostException.class,
                () -> BotWebhookService.guardedLookup(outboundUrlPolicy, "169.254.169.254"));
    }

    @Test
    void guardedLookupAllowsPublicAddress() throws Exception {
        assertFalse(BotWebhookService.guardedLookup(outboundUrlPolicy, "8.8.8.8").isEmpty());
    }

    @Test
    void guardedLookupAllowsAllowlistedInternalHost() throws Exception {
        // 127.0.0.1 is on the owner allowlist, so the rebind guard permits it.
        assertFalse(BotWebhookService.guardedLookup(outboundUrlPolicy, "127.0.0.1").isEmpty());
    }

    // ---- helpers ----

    private BotWebhookSubscription activeSub(Long id, String callbackUrl) {
        BotWebhookSubscription sub = new BotWebhookSubscription();
        sub.setId(id);
        sub.setBotConfig(bot);
        sub.setChatRoomId(null);
        sub.setCallbackUrl(callbackUrl);
        sub.setIsActive(true);
        return sub;
    }

    private void stubFor(BotWebhookSubscription sub) {
        stubFor(sub, s -> {});
    }

    private void stubFor(BotWebhookSubscription sub, java.util.function.Consumer<BotWebhookSubscription> onSave) {
        when(subscriptionRepository.findByBotConfigIdAndIsActiveTrue(5L)).thenReturn(List.of(sub));
        when(subscriptionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any(BotWebhookSubscription.class))).thenAnswer(inv -> {
            BotWebhookSubscription s = inv.getArgument(0);
            onSave.accept(s);
            return s;
        });
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** A tiny local HTTP server (JDK built-in) that captures one webhook delivery. */
    static final class CapturingServer implements AutoCloseable {
        private final HttpServer server;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String body;
        volatile String signature;
        volatile String timestamp;

        CapturingServer(int responseStatus, String redirectLocation) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", (HttpExchange ex) -> {
                try {
                    signature = ex.getRequestHeaders().getFirst("X-PM-Signature");
                    timestamp = ex.getRequestHeaders().getFirst("X-PM-Timestamp");
                    body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (redirectLocation != null) {
                        ex.getResponseHeaders().set("Location", redirectLocation);
                    }
                    ex.sendResponseHeaders(responseStatus, -1);
                } finally {
                    ex.close();
                    latch.countDown();
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
