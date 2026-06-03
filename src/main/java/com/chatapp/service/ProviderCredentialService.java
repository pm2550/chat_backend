package com.chatapp.service;

import com.chatapp.dto.ProviderCredentialDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.User;
import com.chatapp.repository.ProviderCredentialRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProviderCredentialService {

    private final ProviderCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final CredentialCryptoService cryptoService;
    private final OutboundUrlPolicy outboundUrlPolicy;

    @Transactional
    public ProviderCredentialDto.Response create(Long ownerId, ProviderCredentialDto.CreateRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        ProviderCredential credential = new ProviderCredential();
        credential.setOwner(owner);
        credential.setLlmProvider(request.getLlmProvider());
        credential.setLabel(normalizeLabel(request.getLabel()));
        setSecret(credential, request.getSecret());
        credential.setMemo(request.getMemo());
        applyEndpoint(credential, request.getBaseUrl(), request.getModelOverride());
        credential.setIsActive(true);

        return toDto(credentialRepository.save(credential));
    }

    @Transactional
    public ProviderCredential createForBot(Long ownerId, BotConfig.LLMProvider provider, String label, String secret) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        ProviderCredential credential = new ProviderCredential();
        credential.setOwner(owner);
        credential.setLlmProvider(provider);
        credential.setLabel(normalizeLabel(label));
        setSecret(credential, secret);
        credential.setIsActive(true);
        return credentialRepository.save(credential);
    }

    @Transactional(readOnly = true)
    public List<ProviderCredentialDto.Response> list(Long ownerId, BotConfig.LLMProvider provider) {
        List<ProviderCredential> credentials = provider == null
                ? credentialRepository.findByOwnerIdOrderByUpdatedAtDesc(ownerId)
                : credentialRepository.findByOwnerIdAndLlmProviderAndIsActiveTrueOrderByUpdatedAtDesc(ownerId, provider);
        return credentials.stream().map(this::toDto).toList();
    }

    @Transactional
    public ProviderCredentialDto.Response update(Long ownerId, Long credentialId, ProviderCredentialDto.UpdateRequest request) {
        ProviderCredential credential = getOwnedCredential(ownerId, credentialId);
        if (request.getLabel() != null) {
            credential.setLabel(normalizeLabel(request.getLabel()));
        }
        if (request.getSecret() != null && !request.getSecret().isBlank()) {
            setSecret(credential, request.getSecret());
        }
        if (request.getIsActive() != null) {
            credential.setIsActive(request.getIsActive());
        }
        if (request.getMemo() != null) {
            credential.setMemo(request.getMemo());
        }
        applyEndpoint(credential, request.getBaseUrl(), request.getModelOverride());
        return toDto(credentialRepository.save(credential));
    }

    @Transactional
    public void delete(Long ownerId, Long credentialId) {
        ProviderCredential credential = getOwnedCredential(ownerId, credentialId);
        credentialRepository.delete(credential);
    }

    @Transactional(readOnly = true)
    public ProviderCredential getOwnedCredential(Long ownerId, Long credentialId) {
        return credentialRepository.findByIdAndOwnerId(credentialId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("凭据不存在或无权访问"));
    }

    public ProviderCredential getLatestActiveCredential(Long ownerId, BotConfig.LLMProvider provider) {
        return credentialRepository.findByOwnerIdAndLlmProviderAndIsActiveTrueOrderByUpdatedAtDesc(ownerId, provider)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未配置 " + provider + " 凭据"));
    }

    public String decrypt(ProviderCredential credential) {
        if (credential == null || Boolean.FALSE.equals(credential.getIsActive())) {
            return null;
        }
        return cryptoService.decryptPossiblyLegacy(credential.getEncryptedSecret());
    }

    public String encryptLegacyBotKey(String apiKey) {
        return cryptoService.encrypt(apiKey);
    }

    public String decryptLegacyBotKey(String encryptedOrLegacy) {
        return cryptoService.decryptPossiblyLegacy(encryptedOrLegacy);
    }

    public boolean isEncrypted(String value) {
        return cryptoService.isEncrypted(value);
    }

    public ProviderCredentialDto.Response toDto(ProviderCredential credential) {
        return new ProviderCredentialDto.Response(
                credential.getId(),
                credential.getLlmProvider(),
                credential.getLabel(),
                credential.getSecretLast4(),
                credential.getIsActive(),
                credential.getMemo(),
                credential.getBaseUrl(),
                credential.getModelOverride(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }

    /**
     * Applies an optional endpoint override + default model. {@code null} leaves the
     * field unchanged; blank clears it. A non-blank base_url is validated as a
     * user-supplied outbound URL (SSRF guard) before being stored.
     */
    private void applyEndpoint(ProviderCredential credential, String baseUrl, String modelOverride) {
        if (baseUrl != null) {
            String trimmed = baseUrl.trim();
            if (trimmed.isEmpty()) {
                credential.setBaseUrl(null);
            } else {
                outboundUrlPolicy.assertAllowed(trimmed, OutboundUrlPolicy.Caller.USER_SUPPLIED);
                credential.setBaseUrl(trimmed);
            }
        }
        if (modelOverride != null) {
            String trimmed = modelOverride.trim();
            credential.setModelOverride(trimmed.isEmpty() ? null : trimmed);
        }
    }

    private void setSecret(ProviderCredential credential, String secret) {
        credential.setEncryptedSecret(cryptoService.encrypt(secret));
        credential.setSecretFingerprint(cryptoService.fingerprint(secret));
        credential.setSecretLast4(cryptoService.last4(secret));
    }

    private String normalizeLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("凭据名称不能为空");
        }
        return label.trim();
    }
}
