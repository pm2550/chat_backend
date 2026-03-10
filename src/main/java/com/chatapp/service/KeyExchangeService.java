package com.chatapp.service;

import com.chatapp.dto.KeyBundleDto;
import com.chatapp.entity.User;
import com.chatapp.entity.UserKeyBundle;
import com.chatapp.repository.UserKeyBundleRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class KeyExchangeService {

    private final UserKeyBundleRepository keyBundleRepository;
    private final UserRepository userRepository;

    @Transactional
    public KeyBundleDto uploadKeyBundle(Long userId, KeyBundleDto.UploadRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        UserKeyBundle keyBundle = keyBundleRepository.findByUserId(userId)
                .orElse(new UserKeyBundle());

        keyBundle.setUser(user);
        keyBundle.setIdentityPublicKey(request.getIdentityPublicKey());
        keyBundle.setSignedPreKey(request.getSignedPreKey());
        keyBundle.setSignedPreKeySignature(request.getSignedPreKeySignature());
        keyBundle.setOneTimePreKeys(request.getOneTimePreKeys());

        if (keyBundle.getId() != null) {
            keyBundle.setKeyVersion(keyBundle.getKeyVersion() + 1);
        }

        keyBundle = keyBundleRepository.save(keyBundle);
        log.info("用户 {} 上传了密钥包 (版本: {})", userId, keyBundle.getKeyVersion());

        return toDto(keyBundle);
    }

    public KeyBundleDto getKeyBundle(Long userId) {
        UserKeyBundle keyBundle = keyBundleRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("用户尚未上传密钥"));
        return toDto(keyBundle);
    }

    public boolean hasKeyBundle(Long userId) {
        return keyBundleRepository.existsByUserId(userId);
    }

    @Transactional
    public void deleteKeyBundle(Long userId) {
        keyBundleRepository.deleteByUserId(userId);
        log.info("用户 {} 的密钥包已删除", userId);
    }

    private KeyBundleDto toDto(UserKeyBundle entity) {
        KeyBundleDto dto = new KeyBundleDto();
        dto.setUserId(entity.getUser().getId());
        dto.setIdentityPublicKey(entity.getIdentityPublicKey());
        dto.setSignedPreKey(entity.getSignedPreKey());
        dto.setSignedPreKeySignature(entity.getSignedPreKeySignature());
        dto.setOneTimePreKeys(entity.getOneTimePreKeys());
        dto.setKeyVersion(entity.getKeyVersion());
        return dto;
    }
}
