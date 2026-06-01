package com.chatapp.service;

import com.chatapp.dto.ProviderCredentialDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ProviderCredential;
import com.chatapp.entity.User;
import com.chatapp.repository.ProviderCredentialRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderCredentialServiceTest {

    @Mock private ProviderCredentialRepository credentialRepository;
    @Mock private UserRepository userRepository;

    private ProviderCredentialService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new ProviderCredentialService(
                credentialRepository,
                userRepository,
                new CredentialCryptoService("test-master-key-material-32-bytes-long"));
        owner = new User();
        owner.setId(1L);
        owner.setUsername("alice");
    }

    @Test
    void createEncryptsSecretAndReturnsOnlyMetadata() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(credentialRepository.save(any(ProviderCredential.class))).thenAnswer(inv -> {
            ProviderCredential credential = inv.getArgument(0);
            credential.setId(10L);
            return credential;
        });

        ProviderCredentialDto.CreateRequest request = new ProviderCredentialDto.CreateRequest(
                BotConfig.LLMProvider.OPENAI,
                "prod openai",
                "sk-secret",
                "main");

        ProviderCredentialDto.Response response = service.create(1L, request);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getLabel()).isEqualTo("prod openai");
        assertThat(response.getSecretLast4()).isEqualTo("cret");

        ArgumentCaptor<ProviderCredential> captor = ArgumentCaptor.forClass(ProviderCredential.class);
        verify(credentialRepository).save(captor.capture());
        ProviderCredential saved = captor.getValue();
        assertThat(saved.getEncryptedSecret()).startsWith("v1:");
        assertThat(saved.getEncryptedSecret()).doesNotContain("sk-secret");
        assertThat(service.decrypt(saved)).isEqualTo("sk-secret");
    }

    @Test
    void getOwnedCredentialRejectsOtherOwners() {
        when(credentialRepository.findByIdAndOwnerId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedCredential(1L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据不存在");
    }

    @Test
    void listCanFilterByProvider() {
        ProviderCredential credential = new ProviderCredential();
        credential.setId(2L);
        credential.setOwner(owner);
        credential.setLlmProvider(BotConfig.LLMProvider.HERMES);
        credential.setLabel("hermes");
        credential.setSecretLast4("1234");
        credential.setIsActive(true);
        when(credentialRepository.findByOwnerIdAndLlmProviderAndIsActiveTrueOrderByUpdatedAtDesc(
                1L,
                BotConfig.LLMProvider.HERMES))
                .thenReturn(List.of(credential));

        List<ProviderCredentialDto.Response> result = service.list(1L, BotConfig.LLMProvider.HERMES);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLabel()).isEqualTo("hermes");
    }
}
