package com.chatapp.service;

import com.chatapp.dto.UserProfileUpdateRequest;
import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户资料服务
 */
@Service
@Transactional
public class UserProfileService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileStorageService fileStorageService;

    /**
     * 更新用户资料
     */
    public User updateProfile(Long userId, UserProfileUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 更新显示名称
        if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
            user.setDisplayName(request.getDisplayName().trim());
        }

        // 更新邮箱（需要验证唯一性）
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                // 检查邮箱是否已被其他用户使用
                Optional<User> existingUser = userRepository.findByEmail(newEmail);
                if (existingUser.isPresent() && !existingUser.get().getId().equals(userId)) {
                    throw new RuntimeException("该邮箱已被其他用户使用");
                }
                user.setEmail(newEmail);
            }
        }

        // 更新手机号
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone().trim().isEmpty() ? null : request.getPhone().trim());
        }

        // 更新个人简介
        if (request.getBio() != null) {
            user.setBio(request.getBio().trim().isEmpty() ? null : request.getBio().trim());
        }

        // 更新在线状态
        if (request.getOnlineStatus() != null && !request.getOnlineStatus().trim().isEmpty()) {
            try {
                User.OnlineStatus status = User.OnlineStatus.valueOf(request.getOnlineStatus().toUpperCase());
                user.setOnlineStatus(status);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("无效的在线状态: " + request.getOnlineStatus());
            }
        }

        return userRepository.save(user);
    }

    /**
     * 更新用户头像
     */
    public User updateAvatar(Long userId, MultipartFile avatarFile) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 删除旧头像（如果存在）
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isEmpty()) {
            fileStorageService.deleteFile(user.getAvatarUrl());
        }

        // 上传新头像
        String avatarUrl = fileStorageService.uploadAvatar(avatarFile);
        user.setAvatarUrl(avatarUrl);

        return userRepository.save(user);
    }

    /**
     * 删除用户头像
     */
    public User deleteAvatar(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 删除头像文件
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isEmpty()) {
            fileStorageService.deleteFile(user.getAvatarUrl());
        }

        user.setAvatarUrl(null);
        return userRepository.save(user);
    }

    /**
     * 获取用户资料
     */
    public User getProfile(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    /**
     * 更新用户最后在线时间
     */
    public void updateLastSeen(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastSeen(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    /**
     * 更新用户在线状态
     */
    public User updateOnlineStatus(Long userId, User.OnlineStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        user.setOnlineStatus(status);
        if (status == User.OnlineStatus.OFFLINE) {
            user.setLastSeen(LocalDateTime.now());
        }

        return userRepository.save(user);
    }

    /**
     * 搜索用户（根据用户名或显示名称）
     */
    public java.util.List<User> searchUsers(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        
        return userRepository.searchUsers(
                keyword.trim(), 
                org.springframework.data.domain.PageRequest.of(0, limit)
        ).getContent();
    }
} 