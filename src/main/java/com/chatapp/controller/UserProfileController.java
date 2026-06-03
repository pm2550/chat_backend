package com.chatapp.controller;

import com.chatapp.dto.UserDto;
import com.chatapp.dto.UserProfileUpdateRequest;
import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.service.UserProfileService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户资料控制器
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final UserService userService;

    /**
     * 获取当前用户资料
     */
    @GetMapping
    public ResponseEntity<?> getProfile(Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            User user = userProfileService.getProfile(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", user);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 更新用户资料
     */
    @PutMapping
    public ResponseEntity<?> updateProfile(
            @Valid @RequestBody UserProfileUpdateRequest request,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            User updatedUser = userProfileService.updateProfile(userId, request);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "资料更新成功");
            response.put("data", updatedUser);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 上传/更新头像
     */
    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(
            @RequestParam("avatar") MultipartFile avatarFile,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            User updatedUser = userProfileService.updateAvatar(userId, avatarFile);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "头像上传成功");
            response.put("data", Map.of("avatarUrl", updatedUser.getAvatarUrl()));
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "头像上传失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 删除头像
     */
    @DeleteMapping("/avatar")
    public ResponseEntity<?> deleteAvatar(Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            userProfileService.deleteAvatar(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "头像删除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 更新在线状态
     */
    @PutMapping("/status")
    public ResponseEntity<?> updateOnlineStatus(
            @RequestParam("status") String status,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            User.OnlineStatus onlineStatus = User.OnlineStatus.valueOf(status.toUpperCase());
            User updatedUser = userProfileService.updateOnlineStatus(userId, onlineStatus);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "状态更新成功");
            response.put("data", Map.of("onlineStatus", updatedUser.getOnlineStatus()));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "无效的状态值: " + status);
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 搜索用户
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchUsers(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        try {
            List<User> users = userProfileService.searchUsers(keyword, limit);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", users);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 更新最后在线时间
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<?> updateHeartbeat(Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            Long userId = currentUser.getId();
            userProfileService.updateLastSeen(userId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "心跳更新成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 获取当前用户的全局通知/隐私设置。
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            UserSettings settings = userProfileService.getSettings(currentUser.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", toSettingsMap(settings));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 更新当前用户的全局通知/隐私设置。
     */
    @PutMapping("/settings")
    public ResponseEntity<?> updateSettings(
            @RequestBody UserProfileService.UserSettingsUpdateRequest request,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            UserSettings settings = userProfileService.updateSettings(currentUser.getId(), request);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "设置已更新");
            response.put("data", toSettingsMap(settings));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 上传当前用户的聊天背景。上传成功后立即写入 user_settings.chat_background_custom_url。
     */
    @PostMapping("/chat-background")
    public ResponseEntity<?> uploadChatBackground(
            @RequestParam("background") MultipartFile backgroundFile,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            UserSettings settings = userProfileService.uploadChatBackground(
                    currentUser.getId(),
                    backgroundFile);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "聊天背景上传成功");
            response.put("data", toSettingsMap(settings));
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (IOException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "聊天背景上传失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 修改当前用户密码。
     */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(
            @Valid @RequestBody UserDto.ChangePasswordRequest request,
            Authentication auth) {
        try {
            UserDto currentUser = userService.findByUsername(auth.getName());
            userService.changePassword(currentUser.getId(), request);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "密码修改成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private Map<String, Object> toSettingsMap(UserSettings settings) {
        Map<String, Object> data = new HashMap<>();
        data.put("messageNotificationsEnabled", Boolean.TRUE.equals(settings.getMessageNotificationsEnabled()));
        data.put("showOnlineStatus", Boolean.TRUE.equals(settings.getShowOnlineStatus()));
        data.put("allowFriendRequests", Boolean.TRUE.equals(settings.getAllowFriendRequests()));
        data.put("allowDirectMessages", Boolean.TRUE.equals(settings.getAllowDirectMessages()));
        data.put("readReceiptsEnabled", Boolean.TRUE.equals(settings.getReadReceiptsEnabled()));
        data.put("chatBackgroundPreset", settings.getChatBackgroundPreset());
        data.put("chatBackgroundCustomUrl", settings.getChatBackgroundCustomUrl());
        data.put("avatarFramePreset", settings.getAvatarFramePreset());
        data.put("bubbleStylePreset", settings.getBubbleStylePreset());
        return data;
    }
}
