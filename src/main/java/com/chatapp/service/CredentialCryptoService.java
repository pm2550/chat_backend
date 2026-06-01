package com.chatapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

@Service
@Slf4j
public class CredentialCryptoService {

    private static final String PREFIX = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;

    public CredentialCryptoService(
            @Value("${provider-vault.master-key:${jwt.secret:}}") String configuredKey) {
        byte[] keyBytes = deriveKey(configuredKey);
        this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        this.fingerprintKey = new SecretKeySpec(
                sha256(("fingerprint:" + Base64.getEncoder().encodeToString(keyBytes)).getBytes(StandardCharsets.UTF_8)),
                "HmacSHA256");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Secret must not be blank");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt provider credential", e);
        }
    }

    public String decryptPossiblyLegacy(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!value.startsWith(PREFIX + ":")) {
            return value;
        }
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed encrypted credential");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt provider credential", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX + ":");
    }

    public String fingerprint(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Secret must not be blank");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint provider credential", e);
        }
    }

    public String last4(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String trimmed = secret.trim();
        return trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
    }

    private static byte[] deriveKey(String configuredKey) {
        String material = configuredKey == null ? "" : configuredKey.trim();
        if (material.isBlank()) {
            log.warn("provider-vault.master-key and jwt.secret are blank; using development-only fallback key");
            material = "pmchat-dev-provider-vault-fallback-key";
        }
        byte[] decoded = tryBase64(material);
        byte[] source = decoded.length >= 32 ? decoded : material.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(sha256(source), 32);
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            return new byte[0];
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
