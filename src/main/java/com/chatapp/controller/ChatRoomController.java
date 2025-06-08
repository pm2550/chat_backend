package com.chatapp.controller;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.User;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天室控制器
 */
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
@Slf4j
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final UserService userService;

    /**
     * 创建私聊
     */
    @PostMapping("/private/{friendId}")
    public ResponseEntity<?> createPrivateChat(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.createPrivateChat(currentUser.getId(), friendId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "私聊创建成功");
            response.put("chatRoom", chatRoom);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("创建私聊失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 创建群聊
     */
    @PostMapping("/group")
    public ResponseEntity<?> createGroupChat(@RequestBody CreateGroupRequest request, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.createGroupChat(
                currentUser.getId(), 
                request.getName(), 
                request.getDescription(), 
                request.getMemberIds()
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "群聊创建成功");
            response.put("chatRoom", chatRoom);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("创建群聊失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 加入聊天室
     */
    @PostMapping("/{roomId}/join")
    public ResponseEntity<?> joinChatRoom(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.joinChatRoom(roomId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "成功加入聊天室"));
        } catch (Exception e) {
            log.error("加入聊天室失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 退出聊天室
     */
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<?> leaveChatRoom(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.leaveChatRoom(roomId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "成功退出聊天室"));
        } catch (Exception e) {
            log.error("退出聊天室失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取用户的聊天室列表
     */
    @GetMapping
    public ResponseEntity<?> getUserChatRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            
            Sort sort = sortDir.equalsIgnoreCase("desc") ? 
                Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
            Pageable pageable = PageRequest.of(page, size, sort);
            
            Page<ChatRoom> chatRooms = chatRoomService.getUserChatRooms(currentUser.getId(), pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRooms", chatRooms.getContent());
            response.put("currentPage", chatRooms.getNumber());
            response.put("totalPages", chatRooms.getTotalPages());
            response.put("totalElements", chatRooms.getTotalElements());
            response.put("hasNext", chatRooms.hasNext());
            response.put("hasPrevious", chatRooms.hasPrevious());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室列表失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取聊天室详情
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<?> getChatRoomDetails(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.getChatRoomDetails(roomId, currentUser.getId());
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRoom", chatRoom);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室详情失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取聊天室成员列表
     */
    @GetMapping("/{roomId}/members")
    public ResponseEntity<?> getChatRoomMembers(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            
            // 验证用户权限
            chatRoomService.getChatRoomDetails(roomId, currentUser.getId());
            
            List<ChatRoomMember> members = chatRoomService.getChatRoomMembers(roomId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("members", members);
            response.put("count", members.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室成员失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 搜索公开聊天室
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchPublicChatRooms(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<ChatRoom> chatRooms = chatRoomService.searchPublicChatRooms(keyword, pageable);
            
            Map<String, Object> response = new HashMap<>();
            response.put("chatRooms", chatRooms.getContent());
            response.put("keyword", keyword);
            response.put("currentPage", chatRooms.getNumber());
            response.put("totalPages", chatRooms.getTotalPages());
            response.put("totalElements", chatRooms.getTotalElements());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("搜索聊天室失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新聊天室信息
     */
    @PutMapping("/{roomId}")
    public ResponseEntity<?> updateChatRoom(
            @PathVariable Long roomId,
            @RequestBody UpdateChatRoomRequest request,
            Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.updateChatRoom(
                roomId, 
                currentUser.getId(), 
                request.getName(), 
                request.getDescription(), 
                request.getAvatarUrl()
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "聊天室信息更新成功");
            response.put("chatRoom", chatRoom);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("更新聊天室信息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置/取消管理员
     */
    @PostMapping("/{roomId}/members/{userId}/toggle-admin")
    public ResponseEntity<?> toggleAdmin(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.toggleAdmin(roomId, currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "管理员状态更新成功"));
        } catch (Exception e) {
            log.error("更新管理员状态失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 踢出成员
     */
    @PostMapping("/{roomId}/members/{userId}/kick")
    public ResponseEntity<?> kickMember(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.kickMember(roomId, currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "成员已被踢出"));
        } catch (Exception e) {
            log.error("踢出成员失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 禁言/取消禁言成员
     */
    @PostMapping("/{roomId}/members/{userId}/toggle-mute")
    public ResponseEntity<?> toggleMuteStatus(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.toggleMuteStatus(roomId, currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "禁言状态更新成功"));
        } catch (Exception e) {
            log.error("更新禁言状态失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除聊天室
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<?> deleteChatRoom(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findByUsername(auth.getName());
            chatRoomService.deleteChatRoom(roomId, currentUser.getId());
            
            return ResponseEntity.ok(Map.of("message", "聊天室删除成功"));
        } catch (Exception e) {
            log.error("删除聊天室失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // DTO 类
    public static class CreateGroupRequest {
        private String name;
        private String description;
        private List<Long> memberIds;
        
        // Getters and Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<Long> getMemberIds() { return memberIds; }
        public void setMemberIds(List<Long> memberIds) { this.memberIds = memberIds; }
    }

    public static class UpdateChatRoomRequest {
        private String name;
        private String description;
        private String avatarUrl;
        
        // Getters and Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAvatarUrl() { return avatarUrl; }
        public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    }
} 