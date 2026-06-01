package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMServiceTest {

    @Test
    void resolveApiKeyPrefersVaultCredentialOverEnvDefault() {
        ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
        LLMService service = new LLMService(new ObjectMapper(), credentialService);
        ProviderCredential credential = new ProviderCredential();
        credential.setId(7L);

        BotConfig bot = new BotConfig();
        bot.setProviderCredential(credential);
        when(credentialService.decrypt(credential)).thenReturn("vault-secret");

        assertThat(service.resolveApiKey(bot, "env-secret")).isEqualTo("vault-secret");
    }

    @Test
    void resolveApiKeyDecryptsLegacyBotKeyBeforeEnvDefault() {
        ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
        LLMService service = new LLMService(new ObjectMapper(), credentialService);
        BotConfig bot = new BotConfig();
        bot.setApiKeyEncrypted("v1:ciphertext");
        when(credentialService.decryptLegacyBotKey("v1:ciphertext")).thenReturn("legacy-decrypted");

        assertThat(service.resolveApiKey(bot, "env-secret")).isEqualTo("legacy-decrypted");
    }

    @Test
    void resolveApiKeyFallsBackToEnvironmentDefault() {
        ProviderCredentialService credentialService = mock(ProviderCredentialService.class);
        LLMService service = new LLMService(new ObjectMapper(), credentialService);

        assertThat(service.resolveApiKey(new BotConfig(), "env-secret")).isEqualTo("env-secret");
    }
}
