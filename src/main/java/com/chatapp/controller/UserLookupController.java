package com.chatapp.controller;

import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserRepository;
import com.chatapp.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 精确查找用户（扫码 / 加好友确认页）。只做完全匹配，不做模糊搜索，
 * 返回的资料也不含邮箱、手机号等隐私字段。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserLookupController {

    private final UserRepository userRepository;
    private final UserSettingsRepository userSettingsRepository;

    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String id) {
        String trimmedId = id == null ? "" : id.trim();
        String trimmedUsername = username == null ? "" : username.trim();

        Optional<User> found;
        if (!trimmedId.isEmpty()) {
            long userId;
            try {
                userId = Long.parseLong(trimmedId);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "用户 ID 格式不正确"));
            }
            found = userRepository.findById(userId);
        } else if (!trimmedUsername.isEmpty()) {
            // MySQL 默认排序规则大小写不敏感，这里再做一次 Java 侧比较，保证只返回同名用户。
            found = userRepository.findByUsername(trimmedUsername)
                    .filter(user -> user.getUsername() != null
                            && user.getUsername().equalsIgnoreCase(trimmedUsername));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供用户名或用户 ID"));
        }

        Optional<User> active = found.filter(user -> !Boolean.FALSE.equals(user.getIsActive()));
        if (active.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在"));
        }
        return ResponseEntity.ok(Map.of("user", toPublicProfile(active.get())));
    }

    private Map<String, Object> toPublicProfile(User user) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("displayName", user.getDisplayName());
        profile.put("avatarUrl", user.getAvatarUrl());
        profile.put("bio", user.getBio());
        profile.put("title", user.getTitle());
        profile.put("titleColor", user.getTitleColor());
        profile.put("titleEffect", user.getTitleEffect());
        profile.put("avatarFramePreset", userSettingsRepository.findByUserId(user.getId())
                .map(UserSettings::getAvatarFramePreset)
                .orElse("none"));
        profile.put("onlineStatus", user.getOnlineStatus());
        return profile;
    }
}
