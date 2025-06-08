package com.chatapp.service;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 聊天室服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    /**
     * 创建私聊房间
     */
    public ChatRoom createPrivateChat(Long userId, Long friendId) {
        // 检查是否已存在私聊房间
        ChatRoom existingRoom = chatRoomRepository.findPrivateChatBetween(userId, friendId)
                .orElse(null);
        
        if (existingRoom != null) {
            return existingRoom;
        }

        // 获取用户信息
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("目标用户不存在"));

        // 创建私聊房间
        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setName(user.getDisplayName() + " & " + friend.getDisplayName());
        chatRoom.setRoomType(ChatRoom.RoomType.PRIVATE);
        chatRoom.setCreatedBy(user);
        chatRoom.setIsPrivate(true);
        chatRoom.setMaxMembers(2);

        chatRoom = chatRoomRepository.save(chatRoom);

        // 添加成员
        addMemberToRoom(chatRoom.getId(), userId, ChatRoomMember.MemberRole.MEMBER);
        addMemberToRoom(chatRoom.getId(), friendId, ChatRoomMember.MemberRole.MEMBER);

        log.info("创建私聊房间: {} (用户: {} & {})", chatRoom.getId(), userId, friendId);
        return chatRoom;
    }

    /**
     * 创建群聊房间
     */
    public ChatRoom createGroupChat(Long creatorId, String name, String description, List<Long> memberIds) {
        // 获取创建者信息
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 创建群聊房间
        ChatRoom chatRoom = new ChatRoom();
        chatRoom.setName(name);
        chatRoom.setDescription(description);
        chatRoom.setRoomType(ChatRoom.RoomType.GROUP);
        chatRoom.setCreatedBy(creator);
        chatRoom.setIsPrivate(false);
        chatRoom.setMaxMembers(500);

        chatRoom = chatRoomRepository.save(chatRoom);

        // 添加创建者为管理员
        addMemberToRoom(chatRoom.getId(), creatorId, ChatRoomMember.MemberRole.ADMIN);

        // 添加其他成员
        if (memberIds != null) {
            for (Long memberId : memberIds) {
                if (!memberId.equals(creatorId)) {
                    addMemberToRoom(chatRoom.getId(), memberId, ChatRoomMember.MemberRole.MEMBER);
                }
            }
        }

        log.info("创建群聊房间: {} (创建者: {}, 成员数: {})", 
                chatRoom.getId(), creatorId, memberIds != null ? memberIds.size() : 1);
        return chatRoom;
    }

    /**
     * 加入聊天室
     */
    public void joinChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查是否已是成员
        if (chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("已经是聊天室成员");
        }

        // 检查聊天室是否私有
        if (chatRoom.getIsPrivate()) {
            throw new IllegalArgumentException("无法加入私有聊天室");
        }

        // 检查成员数量限制
        int currentMemberCount = chatRoom.getMemberCount();
        if (currentMemberCount >= chatRoom.getMaxMembers()) {
            throw new IllegalArgumentException("聊天室已满");
        }

        // 添加成员
        addMemberToRoom(roomId, userId, ChatRoomMember.MemberRole.MEMBER);

        log.info("用户 {} 加入聊天室 {}", userId, roomId);
    }

    /**
     * 退出聊天室
     */
    public void leaveChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查是否是成员
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("不是聊天室成员");
        }

        // 如果是私聊，不允许退出
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE) {
            throw new IllegalArgumentException("无法退出私聊");
        }

        // 移除成员
        chatRoomRepository.removeMember(roomId, userId);

        log.info("用户 {} 退出聊天室 {}", userId, roomId);
    }

    /**
     * 添加成员到聊天室
     */
    private void addMemberToRoom(Long roomId, Long userId, ChatRoomMember.MemberRole role) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        ChatRoomMember member = new ChatRoomMember();
        member.setChatRoom(chatRoom);
        member.setUser(user);
        member.setMemberRole(role);
        member.setIsAdmin(role == ChatRoomMember.MemberRole.ADMIN);

        chatRoom.getMembers().add(member);
        chatRoomRepository.save(chatRoom);
    }

    /**
     * 获取用户的聊天室列表
     */
    public Page<ChatRoom> getUserChatRooms(Long userId, Pageable pageable) {
        return chatRoomRepository.findByUserId(userId, pageable);
    }

    /**
     * 获取聊天室成员列表
     */
    public List<ChatRoomMember> getChatRoomMembers(Long roomId) {
        return chatRoomRepository.findMembersByRoomId(roomId);
    }

    /**
     * 搜索公开聊天室
     */
    public Page<ChatRoom> searchPublicChatRooms(String keyword, Pageable pageable) {
        return chatRoomRepository.searchPublicRooms(keyword, pageable);
    }

    /**
     * 获取聊天室详情
     */
    public ChatRoom getChatRoomDetails(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查用户是否有权限查看
        if (chatRoom.getIsPrivate() && !chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("无权限查看此聊天室");
        }

        return chatRoom;
    }

    /**
     * 更新聊天室信息
     */
    public ChatRoom updateChatRoom(Long roomId, Long userId, String name, String description, String avatarUrl) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查权限 - 只有管理员可以修改
        if (!chatRoomRepository.isAdmin(roomId, userId)) {
            throw new IllegalArgumentException("无权限修改聊天室信息");
        }

        // 更新信息
        if (name != null && !name.trim().isEmpty()) {
            chatRoom.setName(name.trim());
        }
        if (description != null) {
            chatRoom.setDescription(description);
        }
        if (avatarUrl != null) {
            chatRoom.setAvatarUrl(avatarUrl);
        }

        chatRoom = chatRoomRepository.save(chatRoom);

        log.info("更新聊天室 {} 信息 (操作者: {})", roomId, userId);
        return chatRoom;
    }

    /**
     * 设置/取消管理员
     */
    public void toggleAdmin(Long roomId, Long operatorId, Long targetUserId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查操作者权限
        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new IllegalArgumentException("无权限执行此操作");
        }

        // 检查目标用户是否是成员
        if (!chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }

        // 切换管理员状态
        chatRoomRepository.toggleAdminStatus(roomId, targetUserId);

        log.info("用户 {} 切换了用户 {} 在聊天室 {} 的管理员状态", operatorId, targetUserId, roomId);
    }

    /**
     * 踢出成员
     */
    public void kickMember(Long roomId, Long operatorId, Long targetUserId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 检查操作者权限
        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new IllegalArgumentException("无权限执行此操作");
        }

        // 不能踢出创建者
        if (chatRoom.getCreatedBy().getId().equals(targetUserId)) {
            throw new IllegalArgumentException("不能踢出聊天室创建者");
        }

        // 移除成员
        chatRoomRepository.removeMember(roomId, targetUserId);

        log.info("用户 {} 踢出了用户 {} (聊天室: {})", operatorId, targetUserId, roomId);
    }

    /**
     * 禁言/取消禁言成员
     */
    public void toggleMuteStatus(Long roomId, Long operatorId, Long targetUserId) {
        // 检查操作者权限
        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new IllegalArgumentException("无权限执行此操作");
        }

        // 检查目标用户是否是成员
        if (!chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }

        // 切换禁言状态
        chatRoomRepository.toggleMuteStatus(roomId, targetUserId);

        log.info("用户 {} 切换了用户 {} 在聊天室 {} 的禁言状态", operatorId, targetUserId, roomId);
    }

    /**
     * 删除聊天室
     */
    public void deleteChatRoom(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        // 只有创建者可以删除聊天室
        if (!chatRoom.getCreatedBy().getId().equals(userId)) {
            throw new IllegalArgumentException("只有创建者可以删除聊天室");
        }

        // 私聊不能删除
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE) {
            throw new IllegalArgumentException("私聊不能删除");
        }

        chatRoomRepository.delete(chatRoom);

        log.info("用户 {} 删除了聊天室 {}", userId, roomId);
    }
} 