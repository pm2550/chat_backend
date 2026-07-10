package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomBot;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.dto.ChatRoomParticipantDto;
import com.chatapp.dto.ChatRoomSummaryDto;
import com.chatapp.dto.MessageDto;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.util.ChatCustomizationPresets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
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
    private final MessageRepository messageRepository;
    private final FileStorageService fileStorageService;
    private final BotConfigRepository botConfigRepository;
    private final ChatRoomBotRepository chatRoomBotRepository;

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
        ensureSystemAgentBinding(chatRoom);

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
        ensureSystemAgentBinding(chatRoom);

        // 添加创建者为群主（OWNER，高于管理员）
        addMemberToRoom(chatRoom.getId(), creatorId, ChatRoomMember.MemberRole.OWNER);

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

    private void ensureSystemAgentBinding(ChatRoom chatRoom) {
        if (chatRoom == null || chatRoom.getId() == null) {
            return;
        }
        Optional<BotConfig> agent =
                botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc("Agent");
        if (agent != null) {
            agent.ifPresent(bot -> ensureRoomBot(chatRoom, bot));
        }
    }

    private void ensureRoomBot(ChatRoom chatRoom, BotConfig agent) {
        chatRoomBotRepository.findByChatRoomIdAndBotConfigId(chatRoom.getId(), agent.getId())
                .orElseGet(() -> {
                    ChatRoomBot binding = new ChatRoomBot();
                    binding.setChatRoom(chatRoom);
                    binding.setBotConfig(agent);
                    binding.setTriggerMode(ChatRoomBot.TriggerMode.MENTION);
                    binding.setRoomNickname(agent.getBotName());
                    binding.setEnabledInRoom(true);
                    binding.setIsActive(true);
                    return chatRoomBotRepository.save(binding);
                });
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
        long currentMemberCount = chatRoomRepository.countChatRoomMembers(roomId);
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
        // F5: 唯一群主不能直接退出（否则群将无主，无法再转让/管理/解散），需先转让群主或解散群聊
        if (chatRoomRepository.isOwner(roomId, userId) && chatRoomRepository.countOwners(roomId) <= 1) {
            throw new IllegalArgumentException("群主退出前需先转让群主或解散群聊");
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
        member.setIsAdmin(role == ChatRoomMember.MemberRole.ADMIN
                || role == ChatRoomMember.MemberRole.OWNER);

        chatRoom.getMembers().add(member);
        chatRoomRepository.save(chatRoom);
    }

    /**
     * 获取用户的聊天室列表
     */
    public Page<ChatRoom> getUserChatRooms(Long userId, Pageable pageable) {
        return getUserChatRooms(userId, pageable, false, false, null);
    }

    /**
     * 获取用户的聊天室列表，可按本人的消息流状态过滤。默认消息 tab 不返回
     * 已移出/已屏蔽会话；联系人 tab 可显式包含这些会话。
     */
    public Page<ChatRoom> getUserChatRooms(Long userId,
                                           Pageable pageable,
                                           boolean includeHidden,
                                           boolean includeBlocked,
                                           ChatRoom.RoomType roomType) {
        Pageable unsortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return chatRoomRepository.findByUserIdWithDisplayState(
                userId,
                includeHidden,
                includeBlocked,
                roomType,
                unsortedPageable);
    }

    @Transactional(readOnly = true)
    public Page<ChatRoomSummaryDto> getUserChatRoomSummaries(Long userId,
                                                             Pageable pageable,
                                                             boolean includeHidden,
                                                             boolean includeBlocked,
                                                             ChatRoom.RoomType roomType) {
        Page<ChatRoom> rooms = getUserChatRooms(
                userId,
                pageable,
                includeHidden,
                includeBlocked,
                roomType);
        if (rooms.isEmpty()) {
            return new PageImpl<>(List.of(), rooms.getPageable(), rooms.getTotalElements());
        }

        List<Long> roomIds = rooms.getContent().stream().map(ChatRoom::getId).toList();
        Map<Long, ChatRoomMember> memberships = new HashMap<>();
        for (ChatRoomMember membership :
                chatRoomRepository.findMembershipsByUserIdAndRoomIds(userId, roomIds)) {
            memberships.put(membership.getChatRoom().getId(), membership);
        }

        Map<Long, Long> memberCounts = new HashMap<>();
        for (ChatRoomRepository.RoomMemberCountProjection count :
                chatRoomRepository.countMembersByRoomIds(roomIds)) {
            memberCounts.put(count.getRoomId(), count.getMemberCount());
        }

        Map<Long, List<ChatRoomParticipantDto>> privateParticipants = new HashMap<>();
        for (ChatRoomRepository.PrivateRoomParticipantProjection participant :
                chatRoomRepository.findPrivateParticipantsByRoomIds(roomIds)) {
            privateParticipants.computeIfAbsent(participant.getRoomId(), ignored -> new ArrayList<>())
                    .add(ChatRoomParticipantDto.builder()
                            .id(participant.getUserId())
                            .username(participant.getUsername())
                            .displayName(participant.getDisplayName())
                            .avatarUrl(participant.getAvatarUrl())
                            .title(participant.getTitle())
                            .titleColor(participant.getTitleColor())
                            .titleEffect(participant.getTitleEffect())
                            .onlineStatus(participant.getOnlineStatus())
                            .lastSeen(participant.getLastSeen())
                            .isActive(participant.getActive())
                            .createdAt(participant.getCreatedAt())
                            .updatedAt(participant.getUpdatedAt())
                            .build());
        }

        Map<Long, Message> latestMessages = new HashMap<>();
        for (Message message : messageRepository.findLatestVisibleMessagesForRooms(userId, roomIds)) {
            latestMessages.put(message.getChatRoom().getId(), message);
        }

        List<ChatRoomSummaryDto> summaries = rooms.getContent().stream().map(room -> {
            ChatRoomMember membership = memberships.get(room.getId());
            Message lastMessage = latestMessages.get(room.getId());
            return ChatRoomSummaryDto.builder()
                    .id(room.getId())
                    .name(room.getName())
                    .description(room.getDescription())
                    .announcement(room.getAnnouncement())
                    .announcementUpdatedAt(room.getAnnouncementUpdatedAt())
                    .announcementUpdatedBy(room.getAnnouncementUpdatedBy())
                    .roomType(room.getRoomType())
                    .avatarUrl(room.getAvatarUrl())
                    .customBackgroundPreset(room.getCustomBackgroundPreset())
                    .customBackgroundUrl(room.getCustomBackgroundUrl())
                    .createdBy(room.getCreatedBy() == null ? null : room.getCreatedBy().getId())
                    .isActive(room.getIsActive())
                    .isPrivate(room.getIsPrivate())
                    .maxMembers(room.getMaxMembers())
                    .anonymousEnabled(room.getAnonymousEnabled())
                    .anonymousTheme(room.getAnonymousTheme())
                    .createdAt(room.getCreatedAt())
                    .updatedAt(room.getUpdatedAt())
                    .participants(privateParticipants.getOrDefault(room.getId(), List.of()))
                    .memberCount(memberCounts.getOrDefault(room.getId(), 0L))
                    .lastMessage(lastMessage == null ? null : MessageDto.fromEntity(lastMessage))
                    .unreadCount(membership == null || membership.getUnreadCount() == null
                            ? 0 : membership.getUnreadCount())
                    .isPinned(membership != null && Boolean.TRUE.equals(membership.getIsPinned()))
                    .isMuted(membership != null && Boolean.TRUE.equals(membership.getIsNotificationMuted()))
                    .hiddenAt(membership == null ? null : membership.getHiddenAt())
                    .isBlocked(membership != null && Boolean.TRUE.equals(membership.getIsBlocked()))
                    .clearedBeforeMessageId(
                            membership == null ? null : membership.getClearedBeforeMessageId())
                    .build();
        }).toList();
        return new PageImpl<>(summaries, rooms.getPageable(), rooms.getTotalElements());
    }

    /**
     * 获取聊天室成员列表
     */
    public List<ChatRoomMember> getChatRoomMembers(Long roomId) {
        return chatRoomRepository.findMembersByRoomId(roomId);
    }

    /**
     * 获取当前用户的房间通知偏好。
     */
    public ChatRoomMember getNotificationSettings(Long roomId, Long userId) {
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("不是聊天室成员");
        }
        return chatRoomRepository.findMember(roomId, userId)
                .orElseThrow(() -> new RuntimeException("聊天室成员不存在"));
    }

    /**
     * 更新当前用户的房间通知偏好。
     */
    public ChatRoomMember updateNotificationSettings(Long roomId, Long userId, Boolean muted, Boolean pinned) {
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("不是聊天室成员");
        }
        if (muted != null) {
            chatRoomRepository.updateNotificationMuted(roomId, userId, muted);
        }
        if (pinned != null) {
            chatRoomRepository.updatePinned(roomId, userId, pinned);
        }
        log.info("用户 {} 更新聊天室 {} 偏好: muted={}, pinned={}", userId, roomId, muted, pinned);
        return getNotificationSettings(roomId, userId);
    }

    /**
     * 更新当前用户自己的会话展示状态。清空记录只推进该用户的可见起点；
     * 隐藏/屏蔽只影响消息流展示和未读，不影响房间成员关系或其他成员历史。
     */
    public ChatRoomMember updateDisplayState(Long roomId, Long userId, String action) {
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("不是聊天室成员");
        }
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("action 不能为空");
        }

        switch (normalized) {
            case "CLEAR", "CLEAR_HISTORY", "CLEAR_CHAT_HISTORY" -> {
                Message lastMessage = messageRepository.findLastMessage(roomId);
                Long clearedBeforeMessageId = lastMessage != null && lastMessage.getId() != null
                        ? lastMessage.getId()
                        : 0L;
                chatRoomRepository.updateClearedBeforeMessageId(roomId, userId, clearedBeforeMessageId);
            }
            case "HIDE", "REMOVE", "REMOVE_FROM_LIST", "HIDE_FROM_LIST" ->
                    chatRoomRepository.hideRoomForMember(roomId, userId, LocalDateTime.now());
            case "UNHIDE", "RESTORE", "SHOW" ->
                    chatRoomRepository.restoreRoomForMember(roomId, userId);
            case "BLOCK", "BLOCK_ROOM" ->
                    chatRoomRepository.blockRoomForMember(roomId, userId, LocalDateTime.now());
            case "UNBLOCK", "UNBLOCK_ROOM" ->
                    chatRoomRepository.unblockRoomForMember(roomId, userId);
            default -> throw new IllegalArgumentException("不支持的会话状态操作: " + action);
        }

        log.info("用户 {} 更新聊天室 {} 展示状态: {}", userId, roomId, normalized);
        return getNotificationSettings(roomId, userId);
    }

    /**
     * 更新群名片。成员可以修改自己的群昵称，管理员可以修改成员昵称和群头衔。
     */
    public ChatRoomMember updateMemberProfile(
            Long roomId,
            Long operatorId,
            Long targetUserId,
            String nickname,
            String memberTitle) {
        if (!chatRoomRepository.isMember(roomId, operatorId)) {
            throw new IllegalArgumentException("不是聊天室成员");
        }
        if (!chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }

        boolean isSelf = operatorId.equals(targetUserId);
        boolean operatorIsAdmin = chatRoomRepository.isAdmin(roomId, operatorId);
        if (!isSelf && !operatorIsAdmin) {
            throw new IllegalArgumentException("无权限修改其他成员名片");
        }
        if (memberTitle != null && !operatorIsAdmin) {
            throw new IllegalArgumentException("只有管理员可以设置群头衔");
        }

        ChatRoomMember member = chatRoomRepository.findMember(roomId, targetUserId)
                .orElseThrow(() -> new RuntimeException("聊天室成员不存在"));

        if (nickname != null) {
            String trimmed = nickname.trim();
            member.setNickname(trimmed.isEmpty() ? null : trimmed);
        }
        if (memberTitle != null) {
            String trimmed = memberTitle.trim();
            member.setMemberTitle(trimmed.isEmpty() ? null : trimmed);
        }

        log.info("用户 {} 更新用户 {} 在聊天室 {} 的群名片", operatorId, targetUserId, roomId);
        return member;
    }

    /**
     * 管理员邀请成员加入群聊
     */
    public void addMember(Long roomId, Long operatorId, Long targetUserId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));

        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE || chatRoom.getIsPrivate()) {
            throw new IllegalArgumentException("私聊不能邀请成员");
        }

        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new IllegalArgumentException("无权限邀请成员");
        }

        if (chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("用户已经是聊天室成员");
        }

        long currentMemberCount = chatRoomRepository.countChatRoomMembers(roomId);
        if (currentMemberCount >= chatRoom.getMaxMembers()) {
            throw new IllegalArgumentException("聊天室已满");
        }

        addMemberToRoom(roomId, targetUserId, ChatRoomMember.MemberRole.MEMBER);

        log.info("用户 {} 邀请用户 {} 加入聊天室 {}", operatorId, targetUserId, roomId);
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
        return updateChatRoom(
                roomId,
                userId,
                name,
                description,
                avatarUrl,
                null,
                false);
    }

    /**
     * 部分更新聊天室信息，公告变更会写入一条系统消息。
     */
    public ChatRoom updateChatRoom(Long roomId,
                                   Long userId,
                                   String name,
                                   String description,
                                   String avatarUrl,
                                   String announcement,
                                   boolean announcementProvided) {
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
        if (announcementProvided) {
            String nextAnnouncement = announcement == null ? "" : announcement.trim();
            String previousAnnouncement = chatRoom.getAnnouncement() == null
                    ? ""
                    : chatRoom.getAnnouncement().trim();
            if (!previousAnnouncement.equals(nextAnnouncement)) {
                chatRoom.setAnnouncement(nextAnnouncement);
                chatRoom.setAnnouncementUpdatedAt(LocalDateTime.now());
                chatRoom.setAnnouncementUpdatedBy(userId);
                User operator = userRepository.findById(userId)
                        .orElseThrow(() -> new RuntimeException("用户不存在"));
                createAnnouncementSystemMessage(chatRoom, operator, nextAnnouncement);
            }
        }

        chatRoom = chatRoomRepository.save(chatRoom);

        log.info("更新聊天室 {} 信息 (操作者: {})", roomId, userId);
        return chatRoom;
    }

    public ChatRoom updateRoomBackgroundPreset(Long roomId, Long operatorId, String preset) {
        ChatRoom chatRoom = getRoomForBackgroundMutation(roomId, operatorId);
        String normalizedPreset = ChatCustomizationPresets.requireBackground(preset);
        String previousUrl = chatRoom.getCustomBackgroundUrl();
        chatRoom.setCustomBackgroundPreset(normalizedPreset);
        chatRoom.setCustomBackgroundUrl(null);
        chatRoom = chatRoomRepository.save(chatRoom);
        if (previousUrl != null && !previousUrl.isBlank()) {
            fileStorageService.deleteFile(previousUrl);
        }
        log.info("用户 {} 设置聊天室 {} 背景预设 {}", operatorId, roomId, normalizedPreset);
        return chatRoom;
    }

    public ChatRoom uploadRoomBackground(Long roomId, Long operatorId, MultipartFile file) throws IOException {
        ChatRoom chatRoom = getRoomForBackgroundMutation(roomId, operatorId);
        String previousUrl = chatRoom.getCustomBackgroundUrl();
        String backgroundUrl = fileStorageService.uploadChatBackground(file);
        chatRoom.setCustomBackgroundUrl(backgroundUrl);
        chatRoom = chatRoomRepository.save(chatRoom);
        if (previousUrl != null && !previousUrl.isBlank()) {
            fileStorageService.deleteFile(previousUrl);
        }
        log.info("用户 {} 上传聊天室 {} 背景", operatorId, roomId);
        return chatRoom;
    }

    public ChatRoom uploadRoomAvatar(Long roomId, Long operatorId, MultipartFile file) throws IOException {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE || Boolean.TRUE.equals(chatRoom.getIsPrivate())) {
            throw new AccessDeniedException("私聊不能设置群头像");
        }
        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new AccessDeniedException("只有管理员可以修改群头像");
        }

        String previousUrl = chatRoom.getAvatarUrl();
        String avatarUrl = fileStorageService.uploadAvatar(file);
        chatRoom.setAvatarUrl(avatarUrl);
        chatRoom = chatRoomRepository.save(chatRoom);
        if (previousUrl != null && !previousUrl.isBlank()) {
            fileStorageService.deleteFile(previousUrl);
        }
        log.info("用户 {} 上传聊天室 {} 群头像", operatorId, roomId);
        return chatRoom;
    }

    public ChatRoom clearRoomBackground(Long roomId, Long operatorId) {
        ChatRoom chatRoom = getRoomForBackgroundMutation(roomId, operatorId);
        String previousUrl = chatRoom.getCustomBackgroundUrl();
        chatRoom.setCustomBackgroundPreset(null);
        chatRoom.setCustomBackgroundUrl(null);
        chatRoom = chatRoomRepository.save(chatRoom);
        if (previousUrl != null && !previousUrl.isBlank()) {
            fileStorageService.deleteFile(previousUrl);
        }
        log.info("用户 {} 清除聊天室 {} 背景覆盖", operatorId, roomId);
        return chatRoom;
    }

    private ChatRoom getRoomForBackgroundMutation(Long roomId, Long operatorId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        if (!chatRoomRepository.isAdmin(roomId, operatorId)) {
            throw new AccessDeniedException("只有管理员可以修改房间背景");
        }
        return chatRoom;
    }

    private void createAnnouncementSystemMessage(ChatRoom chatRoom, User operator, String announcement) {
        String preview = announcement == null || announcement.isBlank()
                ? "公告已清空"
                : announcement.length() > 80 ? announcement.substring(0, 80) + "..." : announcement;

        Message message = new Message();
        message.setChatRoom(chatRoom);
        message.setSender(operator);
        message.setMessageType(Message.MessageType.SYSTEM);
        message.setMessageStatus(Message.MessageStatus.SENT);
        message.setContent("📢 群公告已更新：" + preview);
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);
        chatRoomRepository.incrementUnreadForRoomMembersExcept(chatRoom.getId(), operator.getId());
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
        // F5: 群主身份只能通过转让变更，不能被 toggle-admin 降级（否则会出现无群主的群）
        if (chatRoomRepository.isOwner(roomId, targetUserId)) {
            throw new IllegalArgumentException("不能更改群主的管理员状态，请使用转让群主功能");
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
        // F5: 不能踢出群主（即使群主已通过转让变更，不再等于 createdBy）
        if (chatRoomRepository.isOwner(roomId, targetUserId)) {
            throw new IllegalArgumentException("不能踢出群主");
        }
        // F5: 只有群主能移除管理员（管理员之间不能互踢）
        if (chatRoomRepository.isAdmin(roomId, targetUserId)
                && !chatRoomRepository.isOwner(roomId, operatorId)) {
            throw new IllegalArgumentException("只有群主可以移除管理员");
        }

        // 移除成员
        chatRoomRepository.removeMember(roomId, targetUserId);

        log.info("用户 {} 踢出了用户 {} (聊天室: {})", operatorId, targetUserId, roomId);
    }

    /**
     * 转让群主。仅当前群主可操作；新群主必须是聊天室成员。原群主降为管理员，目标升为群主
     * （同一事务原子完成，保证始终恰好一个群主）。
     */
    public void transferOwnership(Long roomId, Long currentOwnerId, Long newOwnerId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE) {
            throw new IllegalArgumentException("私聊不能转让群主");
        }
        if (!chatRoomRepository.isOwner(roomId, currentOwnerId)) {
            throw new AccessDeniedException("只有群主可以转让群主身份");
        }
        if (currentOwnerId.equals(newOwnerId)) {
            throw new IllegalArgumentException("新群主不能是当前群主");
        }
        if (!chatRoomRepository.isMember(roomId, newOwnerId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }
        chatRoomRepository.assignMemberRole(roomId, currentOwnerId, ChatRoomMember.MemberRole.ADMIN, true);
        chatRoomRepository.assignMemberRole(roomId, newOwnerId, ChatRoomMember.MemberRole.OWNER, true);
        log.info("聊天室 {} 群主由 {} 转让给 {}", roomId, currentOwnerId, newOwnerId);
    }

    /**
     * 设置成员角色（ADMIN/MODERATOR/MEMBER）。仅群主可操作。不能用此方法设 OWNER（请用
     * {@link #transferOwnership}），也不能改动现任群主的角色。
     */
    public void setMemberRole(Long roomId, Long operatorId, Long targetUserId,
                              ChatRoomMember.MemberRole newRole) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE) {
            throw new IllegalArgumentException("私聊不能设置成员角色");
        }
        if (!chatRoomRepository.isOwner(roomId, operatorId)) {
            throw new AccessDeniedException("只有群主可以管理成员角色");
        }
        if (newRole == null || newRole == ChatRoomMember.MemberRole.OWNER) {
            throw new IllegalArgumentException("请使用转让群主功能设置群主");
        }
        if (!chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }
        if (chatRoomRepository.isOwner(roomId, targetUserId)) {
            throw new IllegalArgumentException("不能直接降级群主，请先转让群主");
        }
        boolean isAdmin = newRole == ChatRoomMember.MemberRole.ADMIN;
        chatRoomRepository.assignMemberRole(roomId, targetUserId, newRole, isAdmin);
        log.info("聊天室 {} 群主 {} 将成员 {} 角色设为 {}", roomId, operatorId, targetUserId, newRole);
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

        // 私聊不能删除（先于群主校验，给出正确的提示，且私聊无群主）
        if (chatRoom.getRoomType() == ChatRoom.RoomType.PRIVATE) {
            throw new IllegalArgumentException("私聊不能删除");
        }

        // 只有群主可以删除聊天室（群主可能已通过转让变更，不再等于 createdBy）
        if (!chatRoomRepository.isOwner(roomId, userId)) {
            throw new IllegalArgumentException("只有群主可以删除聊天室");
        }

        chatRoomRepository.delete(chatRoom);

        log.info("用户 {} 删除了聊天室 {}", userId, roomId);
    }
}
