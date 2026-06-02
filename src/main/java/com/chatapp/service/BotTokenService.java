package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Inbound bot tokens (Phase 4 / F1). A token lets an external service (the owner's
 * OpenClaw / a TG/QQ bridge) authenticate to the bot-gateway and post AS the bot.
 * Only the fingerprint + last4 are persisted (via {@link CredentialCryptoService}),
 * exactly like {@code provider_credentials} — the raw token is shown once on rotate.
 */
@Service
@RequiredArgsConstructor
public class BotTokenService {

    private static final String PREFIX = "pmcb_";
    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int RANDOM_LEN = 40;

    private final SecureRandom random = new SecureRandom();
    private final BotConfigRepository botConfigRepository;
    private final CredentialCryptoService cryptoService;

    public String generateRawToken() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    /** Resolve which bot a raw inbound token belongs to (by SHA-256 fingerprint). */
    @Transactional(readOnly = true)
    public Optional<BotConfig> resolveBotByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String fingerprint = cryptoService.fingerprint(rawToken.trim());
        return botConfigRepository.findByInboundTokenFingerprint(fingerprint);
    }

    /**
     * (Re)generate the bot's inbound token. Owner-gated. Returns the raw token, which
     * is shown to the owner exactly once and never stored in plaintext.
     */
    @Transactional
    public String rotateTokenForOwner(Long botId, Long ownerId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("机器人不存在"));
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(ownerId)) {
            throw new AccessDeniedException("只能为自己创建的机器人轮换令牌");
        }
        String raw = generateRawToken();
        bot.setInboundTokenFingerprint(cryptoService.fingerprint(raw));
        bot.setInboundTokenLast4(cryptoService.last4(raw));
        botConfigRepository.save(bot);
        return raw;
    }
}
