package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.User;
import com.chatapp.repository.BotConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotTokenServiceTest {

    @Mock private BotConfigRepository botConfigRepository;
    private final CredentialCryptoService crypto =
            new CredentialCryptoService("test-master-key-material-32-bytes-long");
    private BotTokenService service;

    private User owner;
    private BotConfig bot;

    @BeforeEach
    void setUp() {
        service = new BotTokenService(botConfigRepository, crypto);
        owner = new User();
        owner.setId(1L);
        bot = new BotConfig();
        bot.setId(5L);
        bot.setBotName("bridge-bot");
        bot.setCreatedBy(owner);
    }

    @Test
    void generatedTokenHasPrefixAndIsRandom() {
        String a = service.generateRawToken();
        String b = service.generateRawToken();
        assertTrue(a.startsWith("pmcb_"));
        assertTrue(a.length() > 20);
        assertTrue(!a.equals(b));
    }

    @Test
    void rotateStoresFingerprintAndLast4AndReturnsRawOnce() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        when(botConfigRepository.save(any(BotConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        String raw = service.rotateTokenForOwner(5L, 1L);

        assertTrue(raw.startsWith("pmcb_"));
        assertEquals(crypto.fingerprint(raw), bot.getInboundTokenFingerprint());
        assertEquals(crypto.last4(raw), bot.getInboundTokenLast4());
        verify(botConfigRepository).save(bot);
    }

    @Test
    void rotateRejectsNonOwner() {
        when(botConfigRepository.findById(5L)).thenReturn(Optional.of(bot));
        assertThrows(AccessDeniedException.class, () -> service.rotateTokenForOwner(5L, 999L));
    }

    @Test
    void resolveByTokenFindsBotByFingerprint() {
        // simulate a previously-rotated token
        String raw = service.generateRawToken();
        String fp = crypto.fingerprint(raw);
        when(botConfigRepository.findByInboundTokenFingerprint(fp)).thenReturn(Optional.of(bot));

        Optional<BotConfig> resolved = service.resolveBotByToken("  " + raw + "  ");
        assertTrue(resolved.isPresent());
        assertEquals(5L, resolved.get().getId());
    }

    @Test
    void resolveByBlankTokenIsEmpty() {
        assertTrue(service.resolveBotByToken("  ").isEmpty());
        assertTrue(service.resolveBotByToken(null).isEmpty());
    }
}
