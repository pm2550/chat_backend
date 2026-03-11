package com.chatapp.controller;

import com.chatapp.dto.UserDto;
import com.chatapp.dto.UserProfileUpdateRequest;
import com.chatapp.entity.User;
import com.chatapp.service.UserProfileService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}
