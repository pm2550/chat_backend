package com.chatapp.dto;

import com.chatapp.entity.User;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ChatRoomParticipantDto {
    Long id;
    String username;
    String displayName;
    String avatarUrl;
    String title;
    String titleColor;
    String titleEffect;
    User.OnlineStatus onlineStatus;
    LocalDateTime lastSeen;
    Boolean isActive;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
