package com.chatapp.service;

import com.chatapp.dto.KeyBundleDto;
import com.chatapp.entity.User;
import com.chatapp.entity.UserKeyBundle;
import com.chatapp.repository.UserKeyBundleRepository;
import com.chatapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeyExchangeService")
class KeyExchangeServiceTest {

    @Mock private UserKeyBundleRepository keyBundleRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private KeyExchangeService service;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(42L);
        alice.setUsername("alice");
    }

    private KeyBundleDto.UploadRequest req(String idKey, String spk, String sig, String otks) {
        return new KeyBundleDto.UploadRequest(idKey, spk, sig, otks);
    }

    @Test
    @DisplayName("uploadKeyBundle creates new bundle at version 1 when none exists")
    void upload_creates_new_bundle() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(alice));
        when(keyBundleRepository.findByUserId(42L)).thenReturn(Optional.empty());
        when(keyBundleRepository.save(any(UserKeyBundle.class)))
                .thenAnswer(inv -> {
                    UserKeyBundle b = inv.getArgument(0);
                    b.setId(1L);
                    return b;
                });

        KeyBundleDto dto = service.uploadKeyBundle(42L,
                req("idKey", "spk", "sig", "otk1,otk2"));

        assertEquals(42L, dto.getUserId());
        assertEquals("idKey", dto.getIdentityPublicKey());
        assertEquals("spk", dto.getSignedPreKey());
        assertEquals("sig", dto.getSignedPreKeySignature());
        assertEquals("otk1,otk2", dto.getOneTimePreKeys());
        assertEquals(1, dto.getKeyVersion());
    }

    @Test
    @DisplayName("uploadKeyBundle bumps version when an existing bundle is present")
    void upload_bumps_version() {
        UserKeyBundle existing = new UserKeyBundle();
        existing.setId(7L);
        existing.setUser(alice);
        existing.setKeyVersion(3);
        when(userRepository.findById(42L)).thenReturn(Optional.of(alice));
        when(keyBundleRepository.findByUserId(42L)).thenReturn(Optional.of(existing));
        when(keyBundleRepository.save(any(UserKeyBundle.class))).thenAnswer(inv -> inv.getArgument(0));

        KeyBundleDto dto = service.uploadKeyBundle(42L,
                req("id2", "spk2", "sig2", null));

        ArgumentCaptor<UserKeyBundle> captor = ArgumentCaptor.forClass(UserKeyBundle.class);
        verify(keyBundleRepository).save(captor.capture());
        assertEquals(4, captor.getValue().getKeyVersion());
        assertEquals("id2", captor.getValue().getIdentityPublicKey());
        assertEquals(4, dto.getKeyVersion());
    }

    @Test
    @DisplayName("uploadKeyBundle throws when user missing")
    void upload_missing_user() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.uploadKeyBundle(99L, req("x", "y", "z", null)));
        assertTrue(ex.getMessage().contains("用户不存在"));
    }

    @Test
    @DisplayName("getKeyBundle returns dto for existing bundle")
    void get_returns_dto() {
        UserKeyBundle bundle = new UserKeyBundle();
        bundle.setId(1L);
        bundle.setUser(alice);
        bundle.setIdentityPublicKey("idKey");
        bundle.setSignedPreKey("spk");
        bundle.setSignedPreKeySignature("sig");
        bundle.setOneTimePreKeys("otk1");
        bundle.setKeyVersion(5);
        when(keyBundleRepository.findByUserId(42L)).thenReturn(Optional.of(bundle));

        KeyBundleDto dto = service.getKeyBundle(42L);
        assertEquals(42L, dto.getUserId());
        assertEquals("idKey", dto.getIdentityPublicKey());
        assertEquals(5, dto.getKeyVersion());
    }

    @Test
    @DisplayName("getKeyBundle throws when user has no bundle")
    void get_missing() {
        when(keyBundleRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> service.getKeyBundle(42L));
    }

    @Test
    @DisplayName("hasKeyBundle delegates to repository")
    void has_bundle() {
        when(keyBundleRepository.existsByUserId(42L)).thenReturn(true);
        assertTrue(service.hasKeyBundle(42L));
        when(keyBundleRepository.existsByUserId(42L)).thenReturn(false);
        assertFalse(service.hasKeyBundle(42L));
    }

    @Test
    @DisplayName("deleteKeyBundle delegates to repository")
    void delete_bundle() {
        service.deleteKeyBundle(42L);
        verify(keyBundleRepository).deleteByUserId(42L);
    }
}
