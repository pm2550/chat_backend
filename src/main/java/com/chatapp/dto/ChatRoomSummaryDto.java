package com.chatapp.dto;

import com.chatapp.entity.ChatRoom;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class ChatRoomSummaryDto {
    Long id;
    String name;
    String description;
    String announcement;
    LocalDateTime announcementUpdatedAt;
    Long announcementUpdatedBy;
    ChatRoom.RoomType roomType;
    String avatarUrl;
    String customBackgroundPreset;
    String customBackgroundUrl;
    Long createdBy;
    Boolean isActive;
    Boolean isPrivate;
    Integer maxMembers;
    Boolean anonymousEnabled;
    String anonymousTheme;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    List<ChatRoomParticipantDto> participants;
    long memberCount;
    MessageDto lastMessage;
    int unreadCount;
    boolean isPinned;
    boolean isMuted;
    LocalDateTime hiddenAt;
    boolean isBlocked;
    Long clearedBeforeMessageId;
}
