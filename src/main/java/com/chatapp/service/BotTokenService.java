package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.repository.BotConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    public static final String SCOPE_MESSAGE_SEND = "message:send";
    public static final String SCOPE_MESSAGE_READ = "message:read";
    public static final String SCOPE_ROOM_MANAGE = "room:manage";
    public static final String SCOPE_WORKSPACE_READ = "workspace:read";
    public static final String SCOPE_WORKSPACE_WRITE = "workspace:write";
    public static final String SCOPE_FRIEND_SEND = "friend:send";
    public static final List<String> ALL_SCOPES = List.of(
            SCOPE_MESSAGE_SEND,
            SCOPE_MESSAGE_READ,
            SCOPE_ROOM_MANAGE,
            SCOPE_WORKSPACE_READ,
            SCOPE_WORKSPACE_WRITE,
            SCOPE_FRIEND_SEND);

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
     * Scope gate for long-lived bot gateway tokens. Blank scope strings are legacy
     * tokens from the original gateway and keep only message:send so old integrations
     * can still POST /messages without silently gaining read/manage privileges.
     */
    public boolean hasScope(BotConfig bot, String requiredScope) {
        if (bot == null || requiredScope == null || requiredScope.isBlank()) {
            return false;
        }
        Set<String> scopes = parseScopes(bot.getInboundTokenScopes());
        if (scopes.isEmpty()) {
            return SCOPE_MESSAGE_SEND.equals(requiredScope);
        }
        return scopes.contains(requiredScope);
    }

    public Set<String> parseScopes(String rawScopes) {
        if (rawScopes == null || rawScopes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawScopes.split("[,\\s]+"))
                .map(scope -> scope.trim().toLowerCase(Locale.ROOT))
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public Set<String> updateScopesForOwner(Long botId, Long ownerId, Iterable<String> rawScopes) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("机器人不存在"));
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(ownerId)) {
            throw new AccessDeniedException("只能管理自己创建的机器人令牌");
        }
        Set<String> allowed = Set.copyOf(ALL_SCOPES);
        Set<String> scopes = rawScopes == null
                ? Set.of()
                : java.util.stream.StreamSupport.stream(rawScopes.spliterator(), false)
                        .map(scope -> scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT))
                        .filter(scope -> !scope.isBlank())
                        .peek(scope -> {
                            if (!allowed.contains(scope)) {
                                throw new IllegalArgumentException("未知 scope: " + scope);
                            }
                        })
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        bot.setInboundTokenScopes(scopes.isEmpty() ? null : String.join(",", scopes));
        botConfigRepository.save(bot);
        return Set.copyOf(scopes);
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

    /** Revoke the bot's inbound token while preserving its configured scopes. */
    @Transactional
    public void revokeTokenForOwner(Long botId, Long ownerId) {
        BotConfig bot = botConfigRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("机器人不存在"));
        if (bot.getCreatedBy() == null || !bot.getCreatedBy().getId().equals(ownerId)) {
            throw new AccessDeniedException("只能吊销自己创建的机器人令牌");
        }
        bot.setInboundTokenFingerprint(null);
        bot.setInboundTokenLast4(null);
        botConfigRepository.save(bot);
    }
}
