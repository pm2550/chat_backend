package com.chatapp.service;

import com.chatapp.dto.UserProfileUpdateRequest;
import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserSettingsRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.util.ChatCustomizationPresets;
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

    @Autowired
    private UserSettingsRepository userSettingsRepository;

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

    public UserSettings getSettings(Long userId) {
        return userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));
    }

    public UserSettings updateSettings(Long userId, UserSettingsUpdateRequest request) {
        UserSettings settings = getSettings(userId);
        if (request.getMessageNotificationsEnabled() != null) {
            settings.setMessageNotificationsEnabled(request.getMessageNotificationsEnabled());
        }
        if (request.getShowOnlineStatus() != null) {
            settings.setShowOnlineStatus(request.getShowOnlineStatus());
        }
        if (request.getAllowFriendRequests() != null) {
            settings.setAllowFriendRequests(request.getAllowFriendRequests());
        }
        if (request.getAllowDirectMessages() != null) {
            settings.setAllowDirectMessages(request.getAllowDirectMessages());
        }
        if (request.getReadReceiptsEnabled() != null) {
            settings.setReadReceiptsEnabled(request.getReadReceiptsEnabled());
        }
        if (request.getChatBackgroundPreset() != null) {
            settings.setChatBackgroundPreset(
                    ChatCustomizationPresets.requireBackground(request.getChatBackgroundPreset()));
        }
        if (request.getChatBackgroundCustomUrl() != null) {
            String url = request.getChatBackgroundCustomUrl().trim();
            settings.setChatBackgroundCustomUrl(url.isEmpty() ? null : requireBackgroundUrl(url));
        }
        if (request.getAvatarFramePreset() != null) {
            settings.setAvatarFramePreset(
                    ChatCustomizationPresets.requireAvatarFrame(request.getAvatarFramePreset()));
        }
        if (request.getBubbleStylePreset() != null) {
            settings.setBubbleStylePreset(
                    ChatCustomizationPresets.requireBubbleStyle(request.getBubbleStylePreset()));
        }
        return userSettingsRepository.save(settings);
    }

    public UserSettings uploadChatBackground(Long userId, MultipartFile backgroundFile) throws IOException {
        UserSettings settings = getSettings(userId);
        String previousUrl = settings.getChatBackgroundCustomUrl();
        String backgroundUrl = fileStorageService.uploadChatBackground(backgroundFile);
        settings.setChatBackgroundCustomUrl(backgroundUrl);
        if (previousUrl != null && !previousUrl.isBlank()) {
            fileStorageService.deleteFile(previousUrl);
        }
        return userSettingsRepository.save(settings);
    }

    private UserSettings createDefaultSettings(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        UserSettings settings = new UserSettings();
        settings.setUser(user);
        settings.setMessageNotificationsEnabled(true);
        settings.setShowOnlineStatus(true);
        settings.setAllowFriendRequests(true);
        settings.setAllowDirectMessages(true);
        settings.setReadReceiptsEnabled(true);
        settings.setChatBackgroundPreset(ChatCustomizationPresets.DEFAULT_BACKGROUND);
        settings.setAvatarFramePreset(ChatCustomizationPresets.DEFAULT_AVATAR_FRAME);
        settings.setBubbleStylePreset(ChatCustomizationPresets.DEFAULT_BUBBLE_STYLE);
        return userSettingsRepository.save(settings);
    }

    private String requireBackgroundUrl(String url) {
        if (!url.startsWith("/api/files/background/")) {
            throw new IllegalArgumentException("chatBackgroundCustomUrl 必须来自背景上传接口");
        }
        return url;
    }

    public static class UserSettingsUpdateRequest {
        private Boolean messageNotificationsEnabled;
        private Boolean showOnlineStatus;
        private Boolean allowFriendRequests;
        private Boolean allowDirectMessages;
        private Boolean readReceiptsEnabled;
        private String chatBackgroundPreset;
        private String chatBackgroundCustomUrl;
        private String avatarFramePreset;
        private String bubbleStylePreset;

        public Boolean getMessageNotificationsEnabled() { return messageNotificationsEnabled; }
        public void setMessageNotificationsEnabled(Boolean messageNotificationsEnabled) {
            this.messageNotificationsEnabled = messageNotificationsEnabled;
        }
        public Boolean getShowOnlineStatus() { return showOnlineStatus; }
        public void setShowOnlineStatus(Boolean showOnlineStatus) { this.showOnlineStatus = showOnlineStatus; }
        public Boolean getAllowFriendRequests() { return allowFriendRequests; }
        public void setAllowFriendRequests(Boolean allowFriendRequests) {
            this.allowFriendRequests = allowFriendRequests;
        }
        public Boolean getAllowDirectMessages() { return allowDirectMessages; }
        public void setAllowDirectMessages(Boolean allowDirectMessages) {
            this.allowDirectMessages = allowDirectMessages;
        }
        public Boolean getReadReceiptsEnabled() { return readReceiptsEnabled; }
        public void setReadReceiptsEnabled(Boolean readReceiptsEnabled) {
            this.readReceiptsEnabled = readReceiptsEnabled;
        }
        public String getChatBackgroundPreset() { return chatBackgroundPreset; }
        public void setChatBackgroundPreset(String chatBackgroundPreset) {
            this.chatBackgroundPreset = chatBackgroundPreset;
        }
        public String getChatBackgroundCustomUrl() { return chatBackgroundCustomUrl; }
        public void setChatBackgroundCustomUrl(String chatBackgroundCustomUrl) {
            this.chatBackgroundCustomUrl = chatBackgroundCustomUrl;
        }
        public String getAvatarFramePreset() { return avatarFramePreset; }
        public void setAvatarFramePreset(String avatarFramePreset) {
            this.avatarFramePreset = avatarFramePreset;
        }
        public String getBubbleStylePreset() { return bubbleStylePreset; }
        public void setBubbleStylePreset(String bubbleStylePreset) {
            this.bubbleStylePreset = bubbleStylePreset;
        }
    }
}
