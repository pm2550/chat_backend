package com.chatapp.controller;

import com.chatapp.dto.PublicUserProfile;
import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserSettingsRepository;
import com.chatapp.service.FriendshipService;
import com.chatapp.service.UserPrivacyService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 好友管理控制器
 */
@RestController
@RequestMapping("/api/v1/friends")
@RequiredArgsConstructor
@Slf4j
public class FriendshipController {

    private final FriendshipService friendshipService;
    private final UserService userService;
    private final UserPrivacyService userPrivacyService;
    private final UserSettingsRepository userSettingsRepository;

    /**
     * 发送好友请求
     */
    @PostMapping("/request/{friendId}")
    public ResponseEntity<?> sendFriendRequest(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Friendship friendship = friendshipService.sendFriendRequest(currentUser.getId(), friendId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "好友请求已发送");
            response.put("friendship", toFriendshipSummary(friendship, currentUser.getId()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("发送好友请求失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 接受好友请求
     */
    @PostMapping("/accept/{friendId}")
    public ResponseEntity<?> acceptFriendRequest(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Friendship friendship = friendshipService.acceptFriendRequest(currentUser.getId(), friendId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "已接受好友请求");
            response.put("friendship", toFriendshipSummary(friendship, currentUser.getId()));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("接受好友请求失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 拒绝好友请求
     */
    @PostMapping("/decline/{friendId}")
    public ResponseEntity<?> declineFriendRequest(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            friendshipService.declineFriendRequest(currentUser.getId(), friendId);
            
            return ResponseEntity.ok(Map.of("message", "已拒绝好友请求"));
        } catch (Exception e) {
            log.error("拒绝好友请求失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除好友
     */
    @DeleteMapping("/{friendId}")
    public ResponseEntity<?> removeFriend(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            friendshipService.removeFriend(currentUser.getId(), friendId);
            
            return ResponseEntity.ok(Map.of("message", "已删除好友"));
        } catch (Exception e) {
            log.error("删除好友失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 屏蔽用户
     */
    @PostMapping("/block/{userId}")
    public ResponseEntity<?> blockUser(@PathVariable Long userId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            friendshipService.blockUser(currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "已屏蔽用户"));
        } catch (Exception e) {
            log.error("屏蔽用户失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 取消屏蔽用户
     */
    @PostMapping("/unblock/{userId}")
    public ResponseEntity<?> unblockUser(@PathVariable Long userId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            friendshipService.unblockUser(currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "已取消屏蔽"));
        } catch (Exception e) {
            log.error("取消屏蔽失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置好友备注名
     */
    @PutMapping("/{friendId}/alias")
    public ResponseEntity<?> setFriendAlias(
            @PathVariable Long friendId, 
            @RequestBody Map<String, String> request,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            String alias = request.get("alias");
            friendshipService.setFriendAlias(currentUser.getId(), friendId, alias);
            
            return ResponseEntity.ok(Map.of("message", "备注名已更新"));
        } catch (Exception e) {
            log.error("设置备注名失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 置顶/取消置顶好友
     */
    @PostMapping("/{friendId}/pin")
    public ResponseEntity<?> togglePinFriend(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            friendshipService.togglePinFriend(currentUser.getId(), friendId);
            
            return ResponseEntity.ok(Map.of("message", "置顶状态已更新"));
        } catch (Exception e) {
            log.error("更新置顶状态失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取好友列表
     */
    @GetMapping
    public ResponseEntity<?> getFriends(Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<User> friends = friendshipService.getFriends(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", toUserSummaries(friends, currentUser.getId()));
            response.put("count", friends.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取好友列表失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取收到的好友请求
     */
    @GetMapping("/requests/received")
    public ResponseEntity<?> getPendingFriendRequests(Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<Friendship> requests = friendshipService.getPendingFriendRequests(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("requests", toFriendshipSummaries(requests, currentUser.getId()));
            response.put("count", requests.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取好友请求失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取发送的好友请求
     */
    @GetMapping("/requests/sent")
    public ResponseEntity<?> getSentFriendRequests(Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<Friendship> requests = friendshipService.getSentFriendRequests(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("requests", toFriendshipSummaries(requests, currentUser.getId()));
            response.put("count", requests.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取发送的好友请求失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 搜索好友
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchFriends(@RequestParam String keyword, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<User> friends = friendshipService.searchFriends(currentUser.getId(), keyword);
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", toUserSummaries(friends, currentUser.getId()));
            response.put("count", friends.size());
            response.put("keyword", keyword);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("搜索好友失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取置顶好友
     */
    @GetMapping("/pinned")
    public ResponseEntity<?> getPinnedFriends(Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            List<User> friends = friendshipService.getPinnedFriends(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", toUserSummaries(friends, currentUser.getId()));
            response.put("count", friends.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取置顶好友失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 检查好友关系
     */
    @GetMapping("/check/{userId}")
    public ResponseEntity<?> checkFriendship(@PathVariable Long userId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            boolean areFriends = friendshipService.areFriends(currentUser.getId(), userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("areFriends", areFriends);
            response.put("userId", userId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("检查好友关系失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取好友统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getFriendStats(Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Long friendCount = friendshipService.getFriendCount(currentUser.getId());
            List<Friendship> pendingRequests = friendshipService.getPendingFriendRequests(currentUser.getId());
            List<Friendship> sentRequests = friendshipService.getSentFriendRequests(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("friendCount", friendCount);
            response.put("pendingRequestCount", pendingRequests.size());
            response.put("sentRequestCount", sentRequests.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取好友统计失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> toUserSummaries(List<User> users, Long viewerId) {
        Map<Long, String> avatarFrames = avatarFramesFor(users);
        Set<Long> hidden = userPrivacyService.usersHidingOnlineStatus(users.stream().map(User::getId).toList());
        return users.stream()
                .map(user -> toUserSummary(user, viewerId, hidden, avatarFrames))
                .toList();
    }

    private List<Map<String, Object>> toFriendshipSummaries(List<Friendship> friendships, Long viewerId) {
        List<User> people = friendships.stream()
                .flatMap(friendship -> Stream.of(friendship.getUser(), friendship.getFriend()))
                .toList();
        Map<Long, String> avatarFrames = avatarFramesFor(people);
        Set<Long> hidden = userPrivacyService.usersHidingOnlineStatus(
                people.stream().map(User::getId).distinct().toList());
        return friendships.stream()
                .map(friendship -> toFriendshipSummary(friendship, viewerId, hidden, avatarFrames))
                .toList();
    }

    private Map<String, Object> toFriendshipSummary(Friendship friendship, Long viewerId) {
        return toFriendshipSummaries(List.of(friendship), viewerId).get(0);
    }

    private Map<String, Object> toFriendshipSummary(Friendship friendship,
                                                    Long viewerId,
                                                    Set<Long> hidingOnlineStatus,
                                                    Map<Long, String> avatarFrames) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", friendship.getId());
        summary.put("status", friendship.getStatus().name());
        summary.put("statusDescription", friendship.getStatus().getDescription());
        summary.put("user", toUserSummary(friendship.getUser(), viewerId, hidingOnlineStatus, avatarFrames));
        summary.put("friend", toUserSummary(friendship.getFriend(), viewerId, hidingOnlineStatus, avatarFrames));
        summary.put("friendAlias", friendship.getFriendAlias());
        summary.put("isBlocked", friendship.getIsBlocked());
        summary.put("isPinned", friendship.getIsPinned());
        summary.put("createdAt", friendship.getCreatedAt());
        summary.put("updatedAt", friendship.getUpdatedAt());
        summary.put("acceptedAt", friendship.getAcceptedAt());
        return summary;
    }

    /**
     * 一次查询解析一批用户的头像框，列表接口不再逐个用户查 user_settings。
     */
    private Map<Long, String> avatarFramesFor(Collection<User> users) {
        List<Long> userIds = users.stream()
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userSettingsRepository.findByUserIdIn(userIds).stream()
                .filter(settings -> settings.getUser() != null && settings.getAvatarFramePreset() != null)
                .collect(Collectors.toMap(
                        settings -> settings.getUser().getId(),
                        UserSettings::getAvatarFramePreset,
                        (first, second) -> first));
    }

    /** 关了"显示在线状态"的人，对别人一律显示离线、不给最后在线时间；本人不受影响。 */
    private Map<String, Object> toUserSummary(User user,
                                              Long viewerId,
                                              Set<Long> hidingOnlineStatus,
                                              Map<Long, String> avatarFrames) {
        boolean hidePresence = !user.getId().equals(viewerId) && hidingOnlineStatus.contains(user.getId());
        // 好友请求的双方可能还是陌生人：只给公开资料，邮箱、手机号不出现在任何一方的列表里。
        Map<String, Object> summary = PublicUserProfile.of(user);
        summary.put("avatarFramePreset", avatarFrames.getOrDefault(user.getId(), "none"));
        if (hidePresence) {
            summary.put("onlineStatus", User.OnlineStatus.OFFLINE);
            summary.put("lastSeen", null);
        }
        return summary;
    }
}
