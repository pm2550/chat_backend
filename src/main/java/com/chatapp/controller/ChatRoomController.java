package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.UserSettingsRepository;
import com.chatapp.service.ChatRoomService;
import com.chatapp.service.MessageService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天室控制器
 */
@RestController
@RequestMapping({"/api/v1/chat-rooms", "/api/v1/rooms"})
@RequiredArgsConstructor
@Slf4j
public class ChatRoomController {

    private final ChatRoomService chatRoomService;
    private final UserService userService;
    private final MessageService messageService;
    private final UserSettingsRepository userSettingsRepository;
    private final RawWebSocketHandler webSocketHandler;
    private final com.chatapp.service.ModerationService moderationService;

    /**
     * 创建私聊
     */
    @PostMapping("/private/{friendId}")
    public ResponseEntity<?> createPrivateChat(@PathVariable Long friendId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
            
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
            
            // 验证用户权限
            chatRoomService.getChatRoomDetails(roomId, currentUser.getId());
            
            List<ChatRoomMember> members = chatRoomService.getChatRoomMembers(roomId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("members", toMemberSummaries(members));
            response.put("count", members.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取聊天室成员失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户在聊天室内被 @ 的消息。
     */
    @GetMapping({"/{roomId}/mentions/me", "/{roomId}/mentioned-me"})
    public ResponseEntity<?> getMentionedMessages(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Pageable pageable = PageRequest.of(
                    page,
                    size,
                    Sort.by("createdAt").descending());
            Page<Message> messages = messageService.getMentionedMessages(
                    roomId,
                    currentUser.getId(),
                    pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("messages", messages.getContent().stream()
                    .map(MessageDto::fromEntity)
                    .toList());
            response.put("currentPage", messages.getNumber());
            response.put("totalPages", messages.getTotalPages());
            response.put("totalElements", messages.getTotalElements());
            response.put("hasNext", messages.hasNext());
            response.put("hasPrevious", messages.hasPrevious());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取 @ 我的消息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取当前用户在聊天室内的通知偏好。
     */
    @GetMapping("/{roomId}/notification-settings")
    public ResponseEntity<?> getNotificationSettings(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoomMember settings = chatRoomService.getNotificationSettings(roomId, currentUser.getId());
            return ResponseEntity.ok(toNotificationSettings(settings, roomId, currentUser.getId()));
        } catch (Exception e) {
            log.error("获取通知偏好失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新当前用户在聊天室内的通知偏好。
     */
    @PutMapping("/{roomId}/notification-settings")
    public ResponseEntity<?> updateNotificationSettings(
            @PathVariable Long roomId,
            @RequestBody NotificationSettingsRequest request,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoomMember settings = chatRoomService.updateNotificationSettings(
                    roomId,
                    currentUser.getId(),
                    request.getMuted(),
                    request.getPinned());
            return ResponseEntity.ok(toNotificationSettings(settings, roomId, currentUser.getId()));
        } catch (Exception e) {
            log.error("更新通知偏好失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 更新成员群名片。成员可改自己的群昵称，管理员可改成员昵称和群头衔。
     */
    @PutMapping("/{roomId}/members/{userId}/profile")
    public ResponseEntity<?> updateMemberProfile(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            @RequestBody MemberProfileRequest request,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoomMember member = chatRoomService.updateMemberProfile(
                    roomId,
                    currentUser.getId(),
                    userId,
                    request.getNickname(),
                    request.getMemberTitle());

            Map<String, Object> response = new HashMap<>();
            response.put("message", "群名片更新成功");
            response.put("member", toMemberSummary(member));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("更新群名片失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 邀请成员加入群聊
     */
    @PostMapping("/{roomId}/members/{userId}")
    public ResponseEntity<?> addMember(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            chatRoomService.addMember(roomId, currentUser.getId(), userId);

            List<ChatRoomMember> members = chatRoomService.getChatRoomMembers(roomId);
            Map<String, Object> response = new HashMap<>();
            response.put("message", "成员已加入群聊");
            response.put("members", toMemberSummaries(members));
            response.put("count", members.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("邀请成员失败: {}", e.getMessage());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
     * 部分更新聊天室信息。用于群公告/群描述等桌面端设置项。
     */
    @PatchMapping("/{roomId}")
    public ResponseEntity<?> patchChatRoom(
            @PathVariable Long roomId,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            boolean announcementProvided = request.containsKey("announcement");
            ChatRoom chatRoom = chatRoomService.updateChatRoom(
                    roomId,
                    currentUser.getId(),
                    optionalString(request, "name"),
                    optionalString(request, "description"),
                    optionalString(request, "avatarUrl"),
                    optionalString(request, "announcement"),
                    announcementProvided);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "聊天室信息更新成功");
            response.put("chatRoom", chatRoom);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("部分更新聊天室信息失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String optionalString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 管理员设置房间背景预设。选择预设会清除房间级自定义上传背景。
     */
    @PutMapping("/{roomId}/background-preset")
    public ResponseEntity<?> updateRoomBackgroundPreset(
            @PathVariable Long roomId,
            @RequestBody BackgroundPresetRequest request,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.updateRoomBackgroundPreset(
                    roomId,
                    currentUser.getId(),
                    request.getPreset());
            return roomBackgroundResponse("房间背景已更新", chatRoom);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("更新房间背景预设失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 管理员上传房间背景。兼容 background/file 两个 multipart 字段名。
     */
    @PostMapping("/{roomId}/background-upload")
    public ResponseEntity<?> uploadRoomBackground(
            @PathVariable Long roomId,
            @RequestParam(value = "background", required = false) MultipartFile backgroundFile,
            @RequestParam(value = "file", required = false) MultipartFile file,
            Authentication auth) {
        try {
            MultipartFile upload = backgroundFile != null ? backgroundFile : file;
            if (upload == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择背景图片"));
            }
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.uploadRoomBackground(
                    roomId,
                    currentUser.getId(),
                    upload);
            return roomBackgroundResponse("房间背景已上传", chatRoom);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
        } catch (Exception e) {
            log.error("上传房间背景失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 管理员清除房间背景覆盖，恢复用户级或默认背景。
     */
    @DeleteMapping("/{roomId}/background")
    public ResponseEntity<?> clearRoomBackground(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.clearRoomBackground(roomId, currentUser.getId());
            return roomBackgroundResponse("房间背景已清除", chatRoom);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("清除房间背景失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> roomBackgroundResponse(String message, ChatRoom chatRoom) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", message);
        response.put("chatRoom", chatRoom);
        response.put("customBackgroundPreset", chatRoom.getCustomBackgroundPreset());
        response.put("customBackgroundUrl", chatRoom.getCustomBackgroundUrl());
        return ResponseEntity.ok(response);
    }

    /**
     * 管理员上传群头像。兼容 avatar/file 两个 multipart 字段名。
     */
    @PostMapping("/{roomId}/avatar")
    public ResponseEntity<?> uploadRoomAvatar(
            @PathVariable Long roomId,
            @RequestParam(value = "avatar", required = false) MultipartFile avatarFile,
            @RequestParam(value = "file", required = false) MultipartFile file,
            Authentication auth) {
        try {
            MultipartFile upload = avatarFile != null ? avatarFile : file;
            if (upload == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "请选择群头像图片"));
            }
            User currentUser = userService.findUserByUsername(auth.getName());
            ChatRoom chatRoom = chatRoomService.uploadRoomAvatar(
                    roomId,
                    currentUser.getId(),
                    upload);
            webSocketHandler.broadcastChatRoomUpdated(chatRoom);
            Map<String, Object> response = new HashMap<>();
            response.put("message", "群头像已上传");
            response.put("chatRoom", chatRoom);
            response.put("avatarUrl", chatRoom.getAvatarUrl());
            return ResponseEntity.ok(response);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("上传群头像失败: {}", e.getMessage());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
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
            User currentUser = userService.findUserByUsername(auth.getName());
            chatRoomService.toggleMuteStatus(roomId, currentUser.getId(), userId);
            
            return ResponseEntity.ok(Map.of("message", "禁言状态更新成功"));
        } catch (Exception e) {
            log.error("更新禁言状态失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 转让群主（仅当前群主可操作）。body: { "newOwnerId": <id> }
     */
    @PostMapping("/{roomId}/transfer-ownership")
    public ResponseEntity<?> transferOwnership(
            @PathVariable Long roomId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Object raw = body == null ? null : body.get("newOwnerId");
            if (raw == null) {
                throw new IllegalArgumentException("newOwnerId 不能为空");
            }
            Long newOwnerId = Long.valueOf(raw.toString());
            chatRoomService.transferOwnership(roomId, currentUser.getId(), newOwnerId);
            return ResponseEntity.ok(Map.of("message", "群主已转让"));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("转让群主失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设置成员角色 ADMIN/MODERATOR/MEMBER（仅群主可操作）。body: { "role": "ADMIN" }
     */
    @PutMapping("/{roomId}/members/{userId}/role")
    public ResponseEntity<?> setMemberRole(
            @PathVariable Long roomId,
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Object raw = body == null ? null : body.get("role");
            if (raw == null) {
                throw new IllegalArgumentException("role 不能为空");
            }
            ChatRoomMember.MemberRole role;
            try {
                role = ChatRoomMember.MemberRole.valueOf(raw.toString().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("无效的角色: " + raw);
            }
            chatRoomService.setMemberRole(roomId, currentUser.getId(), userId, role);
            return ResponseEntity.ok(Map.of("message", "成员角色已更新"));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("设置成员角色失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 群主授予/调整房间内机器人的管理权限（NONE/MUTE/KICK）。body: { "grant": "MUTE" }
     */
    @PutMapping("/{roomId}/bots/{botId}/moderation-grant")
    public ResponseEntity<?> setBotModerationGrant(
            @PathVariable Long roomId,
            @PathVariable Long botId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
            Object raw = body == null ? null : body.get("grant");
            ChatRoomBot.ModerationGrant grant;
            try {
                grant = ChatRoomBot.ModerationGrant.valueOf(
                        raw == null ? "NONE" : raw.toString().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("无效的管理权限: " + raw);
            }
            moderationService.setBotModerationGrant(roomId, currentUser.getId(), botId, grant);
            return ResponseEntity.ok(Map.of("message", "机器人管理权限已更新"));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("设置机器人管理权限失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 删除聊天室
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<?> deleteChatRoom(@PathVariable Long roomId, Authentication auth) {
        try {
            User currentUser = userService.findUserByUsername(auth.getName());
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

    public static class NotificationSettingsRequest {
        private Boolean muted;
        private Boolean pinned;

        public Boolean getMuted() { return muted; }
        public void setMuted(Boolean muted) { this.muted = muted; }
        public Boolean getPinned() { return pinned; }
        public void setPinned(Boolean pinned) { this.pinned = pinned; }
    }

    public static class MemberProfileRequest {
        private String nickname;
        private String memberTitle;

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getMemberTitle() { return memberTitle; }
        public void setMemberTitle(String memberTitle) { this.memberTitle = memberTitle; }
    }

    public static class BackgroundPresetRequest {
        private String preset;

        public String getPreset() { return preset; }
        public void setPreset(String preset) { this.preset = preset; }
    }

    private List<Map<String, Object>> toMemberSummaries(List<ChatRoomMember> members) {
        return members.stream().map(this::toMemberSummary).toList();
    }

    private Map<String, Object> toNotificationSettings(ChatRoomMember member, Long roomId, Long userId) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("roomId", roomId);
        settings.put("userId", userId);
        // Item 5: notification settings reflect the user's OWN notification mute,
        // not the moderation (send-block) mute.
        settings.put("muted", Boolean.TRUE.equals(member.getIsNotificationMuted()));
        settings.put("pinned", Boolean.TRUE.equals(member.getIsPinned()));
        settings.put("notificationLevel", Boolean.TRUE.equals(member.getIsNotificationMuted()) ? "MUTE" : "ALL");
        return settings;
    }

    private Map<String, Object> toMemberSummary(ChatRoomMember member) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", member.getId());
        summary.put("userId", member.getUser().getId());
        summary.put("user", toUserSummary(member.getUser()));
        summary.put("memberRole", member.getMemberRole());
        summary.put("role", member.getMemberRole());
        summary.put("roleDescription", member.getMemberRole() != null ? member.getMemberRole().getDescription() : null);
        summary.put("nickname", member.getNickname());
        summary.put("memberTitle", member.getMemberTitle());
        // Item 5: keep legacy isMuted (now reflects only moderation mutes, since the
        // notification path no longer writes is_muted) for one release of backward compat;
        // new clients should read the explicit isBotMuted / isNotificationMuted fields.
        summary.put("isMuted", member.getIsMuted());
        summary.put("isBotMuted", member.getIsBotMuted());
        summary.put("isNotificationMuted", member.getIsNotificationMuted());
        summary.put("isPinned", member.getIsPinned());
        summary.put("isAdmin", member.getIsAdmin());
        summary.put("joinedAt", member.getJoinedAt());
        summary.put("lastReadMessageId", member.getLastReadMessageId());
        summary.put("unreadCount", member.getUnreadCount());
        return summary;
    }

    private Map<String, Object> toUserSummary(User user) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", user.getId());
        summary.put("username", user.getUsername());
        summary.put("email", user.getEmail());
        summary.put("phone", user.getPhone());
        summary.put("displayName", user.getDisplayName());
        summary.put("avatarUrl", user.getAvatarUrl());
        summary.put("avatarFramePreset", userSettingsRepository.findByUserId(user.getId())
                .map(settings -> settings.getAvatarFramePreset())
                .orElse("none"));
        summary.put("bio", user.getBio());
        summary.put("onlineStatus", user.getOnlineStatus());
        summary.put("lastSeen", user.getLastSeen());
        summary.put("isActive", user.getIsActive());
        summary.put("createdAt", user.getCreatedAt());
        summary.put("updatedAt", user.getUpdatedAt());
        return summary;
    }
}
