package com.chatapp.controller;

import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import com.chatapp.service.FriendshipService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 发送好友请求
     */
    @PostMapping("/request/{friendId}")
    public ResponseEntity<?> sendFriendRequest(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            Friendship friendship = friendshipService.sendFriendRequest(currentUser.getId(), friendId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "好友请求已发送");
            response.put("friendship", friendship);
            
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
            User currentUser = userService.findByUsername(auth.getName());
            Friendship friendship = friendshipService.acceptFriendRequest(currentUser.getId(), friendId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "已接受好友请求");
            response.put("friendship", friendship);
            
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
            List<User> friends = friendshipService.getFriends(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", friends);
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
            User currentUser = userService.findByUsername(auth.getName());
            List<Friendship> requests = friendshipService.getPendingFriendRequests(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("requests", requests);
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
            User currentUser = userService.findByUsername(auth.getName());
            List<Friendship> requests = friendshipService.getSentFriendRequests(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("requests", requests);
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
            User currentUser = userService.findByUsername(auth.getName());
            List<User> friends = friendshipService.searchFriends(currentUser.getId(), keyword);
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", friends);
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
            User currentUser = userService.findByUsername(auth.getName());
            List<User> friends = friendshipService.getPinnedFriends(currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("friends", friends);
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
            User currentUser = userService.findByUsername(auth.getName());
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
            User currentUser = userService.findByUsername(auth.getName());
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
} 