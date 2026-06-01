package com.chatapp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialCryptoServiceTest {

    @Test
    void encryptDecryptRoundTripDoesNotExposePlaintext() {
        CredentialCryptoService service = new CredentialCryptoService("test-master-key-material-32-bytes-long");

        String encrypted = service.encrypt("sk-live-secret");

        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("sk-live-secret");
        assertThat(service.decryptPossiblyLegacy(encrypted)).isEqualTo("sk-live-secret");
        assertThat(service.isEncrypted(encrypted)).isTrue();
    }

    @Test
    void legacyPlaintextIsReturnedForBackwardCompatibility() {
        CredentialCryptoService service = new CredentialCryptoService("test-master-key-material-32-bytes-long");

        assertThat(service.decryptPossiblyLegacy("legacy-secret")).isEqualTo("legacy-secret");
        assertThat(service.isEncrypted("legacy-secret")).isFalse();
    }

    @Test
    void fingerprintIsStableAndDoesNotRevealSecret() {
        CredentialCryptoService service = new CredentialCryptoService("test-master-key-material-32-bytes-long");

        String first = service.fingerprint("secret-one");
        String second = service.fingerprint("secret-one");
        String third = service.fingerprint("secret-two");

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(third);
        assertThat(first).doesNotContain("secret-one");
        assertThat(service.last4("abcd1234")).isEqualTo("1234");
    }
}
